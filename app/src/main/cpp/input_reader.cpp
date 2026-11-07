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
#include <thread>
#include <condition_variable>
#include <climits>
#include <sys/resource.h>
#include <sys/syscall.h>

namespace {
struct Node {
    std::string path, name, reason, readError, grabError;
    input_id ident{};
    bool keyboard=false, mouse=false, touch=false, accepted=false, external=false;
};
struct Device { int fd, id; std::string path, name, kind; bool grabbed = false; bool dropped = false; uint64_t inode; Node info{}; };
std::mutex lock, scanMutex, controlMutex;
std::condition_variable captured;
std::thread captureWorker;
bool captureStop=false;
std::vector<int> pendingEvents;
uint64_t queueOverflows=0, coalescedMotions=0, lifecycle=0;
int captureNice=0,capturePriorityError=0;
bool capturePriorityApplied=false,captureAlive=false;
uint64_t rawEvents=0,rawReadCalls=0,maxQueueDepth=0,lastCaptureGapMaxUs=0;
std::chrono::steady_clock::time_point previousCapture;
#ifdef MAPPER_INPUT_TEST_MANUAL_CAPTURE
bool manualCapture=true;
#endif
std::vector<Device> devices;
bool grabbing = false;
bool topologyLost = false;
int nextId = 1;
std::string lastError, scanError, topologyError;
std::vector<Node> inventory;
bool directoryReadable=false, inventoryTruncated=false;
size_t nodeCount=0, externalCount=0, nextPollDevice=0;
uint64_t budgetYields=0, syncDrops=0, disconnects=0, readErrors=0, staleBudgetYields=0;
std::string lastFault;
constexpr size_t POLL_EVENT_BUDGET=1024, DEVICE_EVENT_BUDGET=64;
constexpr size_t MAX_NODES=64;
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
void grabFailure(Device& device,const std::string& message) {
    device.info.grabError=message;
    for(auto& node:inventory)if(node.path==device.path)node.grabError=message;
}
void scan(bool force) {
    std::lock_guard<std::mutex> scanning(scanMutex);
    auto now = std::chrono::steady_clock::now();
    if (!force && now - lastScan < std::chrono::seconds(1)) return;
    lastScan=now;
    std::vector<Device> known;
    uint64_t epoch;
    {std::lock_guard<std::mutex> guard(lock);known=devices;epoch=lifecycle;}
    struct stat directory{};
    bool directoryStat=stat("/dev/input",&directory)==0;
    static struct stat previousDirectory{};
    static bool haveDirectory=false;
    if(!force&&directoryStat&&haveDirectory&&directory.st_ino==previousDirectory.st_ino
        &&directory.st_mtim.tv_sec==previousDirectory.st_mtim.tv_sec&&directory.st_mtim.tv_nsec==previousDirectory.st_mtim.tv_nsec)return;
    if(directoryStat){previousDirectory=directory;haveDirectory=true;}else haveDirectory=false;
    known.erase(std::remove_if(known.begin(),known.end(),[](const Device& d){struct stat st{};return stat(d.path.c_str(),&st)!=0||st.st_ino!=d.inode;}),known.end());
    std::vector<Device> additions;
    std::vector<Node> inventory;
    size_t nodeCount=0,externalCount=0;
    bool inventoryTruncated=false,directoryReadable=false;
    std::string scanError;
    auto remember=[&](Node node){if(node.external)externalCount++;if(inventory.size()<MAX_NODES)inventory.push_back(std::move(node));else inventoryTruncated=true;};
    auto readFailure=[&](Node& node,const char* operation){node.readError=std::string(operation)+": "+strerror(errno);node.reason="设备信息读取失败";if(scanError.empty())scanError=node.path+" "+node.readError;};
    DIR* dir=opendir("/dev/input");
    if(!dir){std::lock_guard<std::mutex> guard(lock);::scanError=std::string("无法读取 /dev/input: ")+strerror(errno);::directoryReadable=false;return;}
    directoryReadable=true;
    for(;;) {
        errno=0;
        dirent* entry=readdir(dir);
        if(!entry){if(errno&&scanError.empty())scanError=std::string("读取 /dev/input 目录失败: ")+strerror(errno);break;}
        if(strncmp(entry->d_name,"event",5))continue;
        nodeCount++;
        std::string path=std::string("/dev/input/")+entry->d_name;
        auto prior=std::find_if(known.begin(),known.end(),[&](const Device& d){return d.path==path;});
        if(prior!=known.end()){remember(prior->info);continue;}
        Node node;node.path=path;
        int fd=open(path.c_str(),O_RDONLY|O_NONBLOCK|O_CLOEXEC);
        if(fd<0){readFailure(node,"open");remember(std::move(node));continue;}
        char name[256]{};
        if(ioctl(fd,EVIOCGNAME(sizeof(name)-1),name)>=0)node.name=name;
        unsigned long types[(EV_MAX+8*sizeof(unsigned long))/(8*sizeof(unsigned long))]{};
        unsigned long keys[(KEY_MAX+8*sizeof(unsigned long))/(8*sizeof(unsigned long))]{};
        unsigned long rel[(REL_MAX+8*sizeof(unsigned long))/(8*sizeof(unsigned long))]{};
        unsigned long abs[(ABS_MAX+8*sizeof(unsigned long))/(8*sizeof(unsigned long))]{};
        bool readable=true;
        if(ioctl(fd,EVIOCGID,&node.ident)<0){readFailure(node,"EVIOCGID");readable=false;}
        if(readable){node.external=node.ident.bustype==BUS_USB||node.ident.bustype==BUS_BLUETOOTH;}
        if(readable&&ioctl(fd,EVIOCGBIT(0,sizeof(types)),types)<0){readFailure(node,"EVIOCGBIT(types)");readable=false;}
        if(readable&&bit(types,EV_KEY)&&ioctl(fd,EVIOCGBIT(EV_KEY,sizeof(keys)),keys)<0){readFailure(node,"EVIOCGBIT(keys)");readable=false;}
        if(readable&&bit(types,EV_REL)&&ioctl(fd,EVIOCGBIT(EV_REL,sizeof(rel)),rel)<0){readFailure(node,"EVIOCGBIT(relative axes)");readable=false;}
        if(readable&&bit(types,EV_ABS)&&ioctl(fd,EVIOCGBIT(EV_ABS,sizeof(abs)),abs)<0){readFailure(node,"EVIOCGBIT(absolute axes)");readable=false;}
        if(!readable){close(fd);remember(std::move(node));continue;}
        node.touch=bit(keys,BTN_TOUCH)||bit(abs,ABS_MT_POSITION_X);
        node.keyboard=bit(keys,KEY_Q)&&bit(keys,KEY_A)&&bit(keys,KEY_SPACE);
        node.mouse=bit(rel,REL_X)&&bit(rel,REL_Y)&&bit(keys,BTN_LEFT);
        if(!node.external)node.reason="总线不是USB或蓝牙";
        else if(node.touch)node.reason="触摸设备保护：不独占触屏或触摸板";
        else if(!node.keyboard&&!node.mouse)node.reason="未满足字母键盘或相对鼠标能力条件";
        else if(known.size()+additions.size()>=MAX_NODES){node.reason="输入设备数量达到上限";inventoryTruncated=true;}
        else {
            struct stat st{};
            if(fstat(fd,&st)!=0){readFailure(node,"fstat");close(fd);remember(std::move(node));continue;}
            node.accepted=true;node.reason="已识别，可尝试独占";
            Device d{fd,0,path,node.name,node.mouse?"mouse":"keyboard",false,false,st.st_ino,node};
            remember(d.info);additions.push_back(std::move(d));continue;
        }
        close(fd);remember(std::move(node));
    }
    closedir(dir);
    std::lock_guard<std::mutex> guard(lock);
    if(epoch!=lifecycle){for(auto& d:additions)close(d.fd);return;}
    for(auto it=devices.begin();it!=devices.end();){
        bool exists=std::any_of(known.begin(),known.end(),[&](const Device& d){return d.id==it->id;});
        if(!exists){if(it->grabbed){topologyLost=true;topologyError=it->path+" 输入节点消失或替换，已安全暂停";disconnects++;}release(*it);close(it->fd);it=devices.erase(it);}else ++it;
    }
    for(auto& d:additions){d.id=nextId++;if(grabbing){if(ioctl(d.fd,EVIOCGRAB,1)==0)d.grabbed=true;else{d.info.grabError=d.path+" 独占失败: "+strerror(errno);topologyError=d.info.grabError;topologyLost=true;}}devices.push_back(std::move(d));}
    ::inventory=std::move(inventory);::nodeCount=nodeCount;::externalCount=externalCount;
    ::inventoryTruncated=inventoryTruncated;::directoryReadable=directoryReadable;::scanError=std::move(scanError);
}
std::string diagnostics() {
    std::vector<Node> inventory;
    bool inventoryTruncated,directoryReadable;
    size_t nodeCount,externalCount,acceptedCount;
    std::string scanError,lastFault,runtime;
    {
        std::lock_guard<std::mutex> guard(lock);inventory=::inventory;inventoryTruncated=::inventoryTruncated;directoryReadable=::directoryReadable;
        nodeCount=::nodeCount;externalCount=::externalCount;acceptedCount=devices.size();scanError=::scanError;lastFault=::lastFault;
        runtime=",\"budgetYields\":"+std::to_string(budgetYields)+",\"syncDrops\":"+std::to_string(syncDrops)
            +",\"disconnects\":"+std::to_string(disconnects)+",\"readErrors\":"+std::to_string(readErrors)
            +",\"staleBudgetYields\":"+std::to_string(staleBudgetYields)+",\"captureAlive\":"+(captureAlive?"true":"false")
            +",\"capturePriorityApplied\":"+(capturePriorityApplied?"true":"false")+",\"captureNice\":"+std::to_string(captureNice)
            +",\"capturePriorityError\":"+std::to_string(capturePriorityError)+",\"rawEvents\":"+std::to_string(rawEvents)
            +",\"rawReadCalls\":"+std::to_string(rawReadCalls)+",\"queueDepth\":"+std::to_string(pendingEvents.size()/4)
            +",\"maxQueueDepth\":"+std::to_string(maxQueueDepth)+",\"coalescedMoves\":"+std::to_string(coalescedMotions)
            +",\"queueOverflows\":"+std::to_string(queueOverflows)+",\"lastCaptureGapMaxUs\":"+std::to_string(lastCaptureGapMaxUs);
    }
    std::string rows="[";bool truncated=inventoryTruncated;
    for(const auto& node:inventory) {
        std::string row="{\"path\":"+quote(node.path)+",\"name\":"+quote(node.name)
            +",\"bus\":"+std::to_string(node.ident.bustype)+",\"vendor\":"+std::to_string(node.ident.vendor)
            +",\"product\":"+std::to_string(node.ident.product)+",\"keyboard\":"+(node.keyboard?"true":"false")
            +",\"mouse\":"+(node.mouse?"true":"false")+",\"touch\":"+(node.touch?"true":"false")
            +",\"accepted\":"+(node.accepted?"true":"false")+",\"reason\":"+quote(node.reason)
            +",\"readError\":"+quote(node.readError)+",\"grabError\":"+quote(node.grabError)+"}";
        if(rows.size()+row.size()>24000){truncated=true;break;}
        if(rows.size()>1)rows+=",";
        rows+=row;
    }
    return "{\"directoryReadable\":"+std::string(directoryReadable?"true":"false")
        +",\"scanError\":"+quote(scanError)+",\"nodeCount\":"+std::to_string(nodeCount)
        +",\"acceptedCount\":"+std::to_string(acceptedCount)+",\"externalCount\":"+std::to_string(externalCount)
        +runtime+",\"lastFault\":"+quote(lastFault)
        +",\"truncated\":"+(truncated?"true":"false")+",\"nodes\":"+rows+"]}";
}
}

namespace {
void inputFault(Device& d,const std::string& reason) {
    if(d.grabbed) {
        topologyLost=true;
        if(topologyError.empty())topologyError=d.path+" "+reason+"，已安全暂停";
    }
}
// Read a bounded slice per ready device. Unread kernel events stay queued for the next call.
// Poll outside the state lock; validate fd + identity under lock before reading.
// The JNI consumer never performs kernel reads.
std::vector<int> pollEvents(int timeout) {
    std::vector<int> events;
    std::vector<pollfd> fds;
    std::vector<int> identities;
    {std::lock_guard<std::mutex> guard(lock);for(auto& d:devices){fds.push_back(pollfd{d.fd,POLLIN,0});identities.push_back(d.id);}}
    int ready=::poll(fds.data(), fds.size(), std::max(0, std::min(timeout, 50)));
    int pollErrno=errno;
    std::lock_guard<std::mutex> guard(lock);
    if(captureStop)return events;
    // Idle poll timeouts are not consumer/capture stalls.
    if(ready==0)previousCapture={};
    if(ready<0 && pollErrno!=EINTR) {
        readErrors++;
        if(grabbing){topologyLost=true;topologyError=std::string("输入设备 poll 失败: ")+strerror(pollErrno)+"，已安全暂停";}
    }
    size_t consumed=0;
    const size_t start=fds.empty()?0:nextPollDevice%fds.size();
    for (size_t visited=0; ready>0 && visited<fds.size() && consumed<POLL_EVENT_BUDGET; visited++) {
        size_t i=(start+visited)%fds.size();
        auto found=std::find_if(devices.begin(),devices.end(),[&](const Device& d){return d.fd==fds[i].fd&&d.id==identities[i];});
        if(found==devices.end())continue;
        auto& d=*found;nextPollDevice=(i+1)%fds.size();
        if (fds[i].revents & (POLLHUP | POLLERR | POLLNVAL)) {
            disconnects++;inputFault(d,"输入节点失联 (poll revents="+std::to_string(fds[i].revents)+")");
            release(d); close(d.fd); d.fd=-1; continue;
        }
        if (!(fds[i].revents & POLLIN)) continue;
        size_t deviceConsumed=0;
        unsigned interrupted=0;
        while(deviceConsumed<DEVICE_EVENT_BUDGET && consumed<POLL_EVENT_BUDGET) {
            input_event batch[64];
            size_t countLimit=std::min(size_t(64),std::min(DEVICE_EVENT_BUDGET-deviceConsumed,POLL_EVENT_BUDGET-consumed));
            rawReadCalls++;
            ssize_t count=read(d.fd,batch,countLimit*sizeof(input_event));
            if(count<0) {
                if(errno==EAGAIN||errno==EWOULDBLOCK)break;
                if(errno==EINTR){if(++interrupted<4)continue;break;}
                readErrors++;inputFault(d,std::string("输入事件 read 失败: ")+strerror(errno));
                release(d);close(d.fd);d.fd=-1;break;
            }
            if(count==0){disconnects++;inputFault(d,"输入事件读取到 EOF");release(d);close(d.fd);d.fd=-1;break;}
            if(size_t(count)%sizeof(input_event)!=0){readErrors++;inputFault(d,"输入事件长度不完整");release(d);close(d.fd);d.fd=-1;break;}
            size_t received=size_t(count)/sizeof(input_event);
            rawEvents+=received;
            auto captureNow=std::chrono::steady_clock::now();
            if(previousCapture.time_since_epoch().count()!=0)lastCaptureGapMaxUs=std::max(lastCaptureGapMaxUs,uint64_t(std::chrono::duration_cast<std::chrono::microseconds>(captureNow-previousCapture).count()));
            previousCapture=captureNow;
            consumed+=received;deviceConsumed+=received;
            for (size_t n=0; n<received; n++) {
                auto& e=batch[n];
                if (!grabbing || !d.grabbed) continue;
                if (e.type == EV_SYN && e.code == SYN_DROPPED) {
                    d.dropped=true;syncDrops++;inputFault(d,"内核事件队列溢出 (SYN_DROPPED)");continue;
                }
                if (d.dropped) { if (e.type == EV_SYN && e.code == SYN_REPORT) d.dropped=false; continue; }
                if ((e.type==EV_KEY)
                        || (e.type==EV_REL && (e.code==REL_X || e.code==REL_Y || e.code==REL_WHEEL))
) {
                    events.insert(events.end(), {d.id, e.type, e.code, e.value});
                }
            }
            if(topologyLost)break;
        }
        // A scheduling budget is normal backpressure, never evidence of disconnect/loss.
        if(deviceConsumed==DEVICE_EVENT_BUDGET || consumed==POLL_EVENT_BUDGET)budgetYields++;
    }
    devices.erase(std::remove_if(devices.begin(), devices.end(), [](const Device& d){return d.fd<0;}), devices.end());
    if (topologyLost) {
        grabbing=false; for (auto& d:devices) release(d);
        events.clear(); events.insert(events.end(), {-1,-1,0,0}); topologyLost=false;
        lastFault=topologyError.empty()?"输入设备状态异常，已安全暂停":topologyError;
        lastError=lastFault;topologyError.clear();
    }
    return events;
}
}
namespace {
constexpr size_t MAX_PENDING_RECORDS=4096;
void queueFault(const std::string& reason) {
    queueOverflows++;lastFault=lastError=reason;grabbing=false;
    for(auto& d:devices)release(d);
    pendingEvents={-1,-1,0,0};
}
void enqueueCaptured(const std::vector<int>& events) {
    std::lock_guard<std::mutex> guard(lock);
    if(captureStop)return;
    if(!events.empty()&&events[1]==-1){pendingEvents=events;captured.notify_all();return;}
    for(size_t i=0;i+3<events.size();i+=4){
        bool motion=events[i+1]==EV_REL&&(events[i+2]==REL_X||events[i+2]==REL_Y),merged=false;
        if(motion){
            for(size_t tail=pendingEvents.size(),checked=0;tail>=4&&checked<2;checked++){
                tail-=4;
                if(pendingEvents[tail]!=events[i]||pendingEvents[tail+1]!=EV_REL||(pendingEvents[tail+2]!=REL_X&&pendingEvents[tail+2]!=REL_Y))break;
                if(pendingEvents[tail+2]==events[i+2]){
                    int64_t sum=int64_t(pendingEvents[tail+3])+events[i+3];
                    if(sum<INT_MIN||sum>INT_MAX){queueFault("鼠标累计位移超过整数范围，已安全暂停");captured.notify_all();return;}
                    pendingEvents[tail+3]=int(sum);coalescedMotions++;merged=true;break;
                }
            }
        }
        if(merged)continue;
        if(pendingEvents.size()/4>=MAX_PENDING_RECORDS){queueFault("本地输入队列已满，已安全暂停（未静默丢弃按键）");captured.notify_all();return;}
        pendingEvents.insert(pendingEvents.end(),events.begin()+i,events.begin()+i+4);
        maxQueueDepth=std::max(maxQueueDepth,uint64_t(pendingEvents.size()/4));
    }
    if(!pendingEvents.empty())captured.notify_all();
}
void captureLoop(){
    int result=setpriority(PRIO_PROCESS,id_t(syscall(SYS_gettid)),-20),priorityErr=result==0?0:errno;
    errno=0;int actual=getpriority(PRIO_PROCESS,id_t(syscall(SYS_gettid)));int actualErr=errno;
    {std::lock_guard<std::mutex> guard(lock);captureAlive=true;capturePriorityApplied=result==0;capturePriorityError=priorityErr?priorityErr:actualErr;captureNice=actual;}
    for(;;){
        {std::lock_guard<std::mutex> guard(lock);if(captureStop||!grabbing)break;}
        auto events=pollEvents(8);enqueueCaptured(events);
    }
    {std::lock_guard<std::mutex> guard(lock);captureAlive=false;captured.notify_all();}
}
void stopCaptureWorker(){
    {std::lock_guard<std::mutex> guard(lock);captureStop=true;grabbing=false;pendingEvents.clear();for(auto& d:devices)release(d);captured.notify_all();}
    if(captureWorker.joinable())captureWorker.join();
}
bool startCaptureWorker(){
#ifdef MAPPER_INPUT_TEST_MANUAL_CAPTURE
    if(manualCapture)return true;
#endif
    try{captureWorker=std::thread(captureLoop);return true;}
    catch(const std::exception& e){std::lock_guard<std::mutex> guard(lock);grabbing=false;captureStop=true;for(auto& d:devices)release(d);lastError=std::string("无法启动输入捕获线程: ")+e.what();return false;}
}
}
extern "C" JNIEXPORT jboolean JNICALL Java_cn_offline_mapper_engine_NativeInput_setGrab(JNIEnv*,jclass,jboolean enable){
    std::lock_guard<std::mutex> controlling(controlMutex);
    if(enable){std::lock_guard<std::mutex> guard(lock);if(grabbing)return JNI_TRUE;}
    stopCaptureWorker();
    if(!enable){std::lock_guard<std::mutex> guard(lock);topologyLost=false;topologyError.clear();return JNI_TRUE;}
    scan(true);
    {
        std::lock_guard<std::mutex> guard(lock);
        if(devices.empty()){lastError=scanError.empty()?"当前未识别到可独占键鼠，请查看设备识别详情":"当前没有可用键鼠，输入节点读取失败："+scanError;return JNI_FALSE;}
        lastError.clear();
        for(auto& d:devices){
            if(ioctl(d.fd,EVIOCGRAB,1)!=0){lastError=d.path+" 独占失败: "+strerror(errno);grabFailure(d,lastError);for(auto& other:devices)release(other);return JNI_FALSE;}
            d.grabbed=true;d.dropped=false;grabFailure(d,"");input_event stale[64];
            for(size_t reads=0;reads<64;reads++){if(read(d.fd,stale,sizeof(stale))<=0)break;if(reads==63)staleBudgetYields++;}
        }
        captureStop=false;grabbing=true;topologyLost=false;topologyError.clear();previousCapture={};
    }
    return startCaptureWorker()?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT jintArray JNICALL Java_cn_offline_mapper_engine_NativeInput_poll(JNIEnv* env,jclass,jint timeout){
    std::vector<int> events;
    {std::unique_lock<std::mutex> guard(lock);captured.wait_for(guard,std::chrono::milliseconds(std::max(0,std::min(int(timeout),50))),[]{return !pendingEvents.empty();});events.swap(pendingEvents);}
    jintArray result=env->NewIntArray(events.size());
    if(result&&!events.empty())env->SetIntArrayRegion(result,0,events.size(),events.data());
    return result;
}
extern "C" JNIEXPORT jstring JNICALL Java_cn_offline_mapper_engine_NativeInput_devices(JNIEnv* env, jclass) {
    scan(false);
    std::vector<Device> snapshot;{std::lock_guard<std::mutex> guard(lock);snapshot=devices;}
    std::string json="[";
    for (size_t i=0;i<snapshot.size();i++) {
        auto& d=snapshot[i]; if(i) json+=",";
        json+="{\"id\":"+std::to_string(d.id)+",\"name\":"+quote(d.name)+",\"path\":"+quote(d.path)
            +",\"kind\":"+quote(d.kind)+",\"grabbed\":"+(d.grabbed?"true":"false")+"}";
    }
    return env->NewStringUTF((json+"]").c_str());
}
extern "C" JNIEXPORT jstring JNICALL Java_cn_offline_mapper_engine_NativeInput_error(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> guard(lock); return env->NewStringUTF(lastError.c_str());
}
extern "C" JNIEXPORT void JNICALL Java_cn_offline_mapper_engine_NativeInput_close(JNIEnv*,jclass){
    std::lock_guard<std::mutex> controlling(controlMutex);stopCaptureWorker();
    std::lock_guard<std::mutex> guard(lock);lifecycle++;for(auto& d:devices)close(d.fd);devices.clear();
}

extern "C" JNIEXPORT jstring JNICALL Java_cn_offline_mapper_engine_NativeInput_diagnostics(JNIEnv* env,jclass) {
    return env->NewStringUTF(diagnostics().c_str());
}
