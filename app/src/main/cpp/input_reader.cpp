#include <jni.h>
#include <linux/input.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>
#include <dirent.h>
#include <cerrno>
#include <cstring>
#include <algorithm>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>

namespace {
struct Device { int fd, id; std::string path, name, kind; bool grabbed = false; bool dropped = false; uint64_t inode; };
std::mutex lock;
std::vector<Device> devices;
bool grabbing = false;
bool topologyLost = false;
int nextId = 1;
std::string lastError;
std::chrono::steady_clock::time_point lastScan;
template<size_t N> bool bit(const unsigned long (&bits)[N], int code) {
    constexpr int B = sizeof(unsigned long) * 8;
    return code >= 0 && size_t(code / B) < N && (bits[code / B] & (1UL << (code % B)));
}
std::string quote(const std::string& s) {
    std::string out = "\"";
    for (unsigned char c : s) {
        if (c == '"' || c == '\\') { out += '\\'; out += c; }
        else if (c < 32) out += ' ';
        else out += c;
    }
    return out + '"';
}
void release(Device& d) { if (d.grabbed) ioctl(d.fd, EVIOCGRAB, 0); d.grabbed = false; }
void scan(bool force) {
    auto now = std::chrono::steady_clock::now();
    if (!force && now - lastScan < std::chrono::seconds(1)) return;
    lastScan = now;
    // Detect path reuse, including rapid unplug/replug between two polls.
    for (auto it = devices.begin(); it != devices.end();) {
        struct stat st{};
        if (stat(it->path.c_str(), &st) != 0 || st.st_ino != it->inode) {
            topologyLost |= it->grabbed; release(*it); close(it->fd); it = devices.erase(it);
        } else ++it;
    }
    DIR* dir = opendir("/dev/input");
    if (!dir) { lastError = std::string("无法读取 /dev/input: ") + strerror(errno); return; }
    dirent* entry;
    while ((entry = readdir(dir))) {
        if (strncmp(entry->d_name, "event", 5)) continue;
        std::string path = std::string("/dev/input/") + entry->d_name;
        if (std::any_of(devices.begin(), devices.end(), [&](const Device& d){return d.path == path;})) continue;
        int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) { lastError = path + ": " + strerror(errno); continue; }
        input_id ident{};
        unsigned long keys[(KEY_MAX + 8 * sizeof(unsigned long)) / (8 * sizeof(unsigned long))]{};
        unsigned long rel[(REL_MAX + 8 * sizeof(unsigned long)) / (8 * sizeof(unsigned long))]{};
        unsigned long abs[(ABS_MAX + 8 * sizeof(unsigned long)) / (8 * sizeof(unsigned long))]{};
        ioctl(fd, EVIOCGID, &ident);
        ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keys)), keys);
        ioctl(fd, EVIOCGBIT(EV_REL, sizeof(rel)), rel);
        ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs)), abs);
        bool external = ident.bustype == BUS_USB || ident.bustype == BUS_BLUETOOTH;
        bool unsafe = bit(keys, BTN_TOUCH) || bit(abs, ABS_MT_POSITION_X);
        bool keyboard = bit(keys, KEY_Q) && bit(keys, KEY_A) && bit(keys, KEY_SPACE);
        bool mouse = bit(rel, REL_X) && bit(rel, REL_Y) && bit(keys, BTN_LEFT);
        if (!external || unsafe || (!keyboard && !mouse)) { close(fd); continue; }
        char name[256]{}; ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name);
        struct stat st{}; fstat(fd, &st);
        Device d{fd, nextId++, path, name, mouse ? "mouse" : "keyboard", false, false, st.st_ino};
        if (grabbing) {
            if (ioctl(fd, EVIOCGRAB, 1) == 0) d.grabbed = true;
            else { lastError = path + " 独占失败: " + strerror(errno); topologyLost = true; }
        }
        devices.push_back(d);
    }
    closedir(dir);
}
}

extern "C" JNIEXPORT jboolean JNICALL Java_cn_offline_mapper_engine_NativeInput_setGrab(JNIEnv*, jclass, jboolean enable) {
    std::lock_guard<std::mutex> guard(lock);
    scan(true); lastError.clear();
    if (!enable) { grabbing = false; topologyLost = false; for (auto& d:devices) release(d); return JNI_TRUE; }
    if (devices.empty()) { lastError = "未发现可独占的 USB/蓝牙键盘或鼠标"; return JNI_FALSE; }
    for (auto& d:devices) {
        if (!d.grabbed && ioctl(d.fd, EVIOCGRAB, 1) != 0) {
            lastError = d.path + " 独占失败: " + strerror(errno);
            for (auto& other:devices) release(other);
            grabbing = false; return JNI_FALSE;
        }
        d.grabbed = true;
        // Drop old presses accumulated while paused before claiming a new session.
        input_event stale[64]; while (read(d.fd, stale, sizeof(stale)) > 0) {}
    }
    grabbing = true; topologyLost = false; return JNI_TRUE;
}
extern "C" JNIEXPORT jintArray JNICALL Java_cn_offline_mapper_engine_NativeInput_poll(JNIEnv* env, jclass, jint timeout) {
    std::lock_guard<std::mutex> guard(lock);
    scan(false);
    std::vector<int> events;
    std::vector<pollfd> fds;
    for (auto& d:devices) fds.push_back(pollfd{d.fd, POLLIN, 0});
    ::poll(fds.data(), fds.size(), std::max(0, std::min(int(timeout), 50)));
    for (size_t i=0; i<fds.size(); i++) {
        auto& d=devices[i];
        if (fds[i].revents & (POLLHUP | POLLERR | POLLNVAL)) {
            topologyLost |= d.grabbed; release(d); close(d.fd); d.fd=-1; continue;
        }
        if (!(fds[i].revents & POLLIN)) continue;
        input_event batch[64]; ssize_t count;
        while ((count = read(d.fd, batch, sizeof(batch))) > 0) {
            for (size_t n=0; n<size_t(count)/sizeof(input_event); n++) {
                auto& e=batch[n];
                if (!grabbing || !d.grabbed) continue;
                if (e.type == EV_SYN && e.code == SYN_DROPPED) {
                    d.dropped=true; topologyLost=true; continue;
                }
                if (d.dropped) { if (e.type == EV_SYN && e.code == SYN_REPORT) d.dropped=false; continue; }
                if ((e.type==EV_KEY)
                        || (e.type==EV_REL && (e.code==REL_X || e.code==REL_Y || e.code==REL_WHEEL))
                        || (e.type==EV_SYN && e.code==SYN_REPORT)) {
                    events.insert(events.end(), {d.id, e.type, e.code, e.value});
                }
            }
            // Bound batches so a very fast device cannot starve the controller.
            if (events.size() >= 4096) { topologyLost=true; break; }
        }
    }
    devices.erase(std::remove_if(devices.begin(), devices.end(), [](const Device& d){return d.fd<0;}), devices.end());
    if (topologyLost) {
        grabbing=false; for (auto& d:devices) release(d);
        events.clear(); events.insert(events.end(), {-1,-1,0,0}); topologyLost=false;
        lastError="输入设备断开或事件丢失，已安全暂停";
    }
    jintArray result=env->NewIntArray(events.size());
    if (result && !events.empty()) env->SetIntArrayRegion(result,0,events.size(),events.data());
    return result;
}
extern "C" JNIEXPORT jstring JNICALL Java_cn_offline_mapper_engine_NativeInput_devices(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> guard(lock); scan(false);
    std::string json="[";
    for (size_t i=0;i<devices.size();i++) {
        auto& d=devices[i]; if(i) json+=",";
        json+="{\"id\":"+std::to_string(d.id)+",\"name\":"+quote(d.name)+",\"path\":"+quote(d.path)
            +",\"kind\":"+quote(d.kind)+",\"grabbed\":"+(d.grabbed?"true":"false")+"}";
    }
    return env->NewStringUTF((json+"]").c_str());
}
extern "C" JNIEXPORT jstring JNICALL Java_cn_offline_mapper_engine_NativeInput_error(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> guard(lock); return env->NewStringUTF(lastError.c_str());
}
extern "C" JNIEXPORT void JNICALL Java_cn_offline_mapper_engine_NativeInput_close(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> guard(lock);
    grabbing=false; for(auto& d:devices){release(d);close(d.fd);} devices.clear();
}
