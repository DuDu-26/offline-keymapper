// Executes the production scanner with deterministic fake kernel I/O; no real input is opened.
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
#include <cstdio>
#include <cstdlib>
#include <thread>
#include <condition_variable>
#include <climits>
#include <sys/resource.h>
#include <sys/syscall.h>

namespace fixture {
struct Input {int bus=BUS_USB;bool keyboard=true,mouse=false,touch=false,relative=false;int failRequest=0;};
std::vector<Input> inputs;
size_t cursor=0;
bool denyDirectory=false,denyOpen=false,denyGrab=false;
int openCalls=0,dirCalls=0,ioctlCalls=0,releaseCalls=0;
std::vector<input_event> streams[70];
size_t offsets[70]{};
int readCalls[70]{}, readErr[70]{}, forcedRevents[70]{};
bool infinite=false,readEOF=false;
int pollError=0;
bool priorityDenied=false;
int setPriority(int,id_t,int){if(priorityDenied){errno=EACCES;return -1;}return 0;}
int getPriority(int,id_t){return priorityDenied?0:-20;}
int pollFD(pollfd* fds,nfds_t count,int) {
 if(pollError){errno=pollError;return -1;}
 int ready=0;
 for(size_t i=0;i<count;i++){
  size_t index=size_t(fds[i].fd-100);
  fds[i].revents=short(forcedRevents[index]);
  if(readEOF||infinite||offsets[index]<streams[index].size()||readErr[index])fds[i].revents|=POLLIN;
  if(fds[i].revents)ready++;
 }
 return ready;
}
DIR* openDir(const char*) {dirCalls++;cursor=0;if(denyDirectory){errno=EACCES;return nullptr;}return reinterpret_cast<DIR*>(1);}
dirent* nextEntry(DIR*) {static dirent entry{};if(cursor>=inputs.size())return nullptr;std::snprintf(entry.d_name,sizeof(entry.d_name),"event%zu",cursor++);return &entry;}
int closeDir(DIR*) {return 0;}
int openFile(const char* path,int) {openCalls++;if(denyOpen){errno=EACCES;return -1;}return 100+std::atoi(std::strrchr(path,'t')+1);}
int closeFile(int) {return 0;}
int statFile(const char*,struct stat* result) {result->st_ino=1;return 0;}
int statFD(int,struct stat* result) {result->st_ino=1;return 0;}
ssize_t readFile(int fd,void* out,size_t size) {
 size_t index=size_t(fd-100);readCalls[index]++;
 if(readEOF)return 0;
 if(readErr[index]){errno=readErr[index];return -1;}
 if(infinite){std::memset(out,0,size);return ssize_t(size);}
 size_t count=std::min(size/sizeof(input_event),streams[index].size()-offsets[index]);
 if(!count){errno=EAGAIN;return -1;}
 std::memcpy(out,streams[index].data()+offsets[index],count*sizeof(input_event));offsets[index]+=count;
 return ssize_t(count*sizeof(input_event));
}
void set(void* out,int value) {auto bits=static_cast<unsigned long*>(out);bits[value/(sizeof(unsigned long)*8)]|=1UL<<(value%(sizeof(unsigned long)*8));}
int query(int fd,unsigned long request,void* out) {
 ioctlCalls++;auto& input=inputs.at(size_t(fd-100));
 if(input.failRequest==1&&request==EVIOCGID){errno=EACCES;return -1;}
 if(input.failRequest==2&&request==EVIOCGBIT(EV_KEY,96)){errno=EACCES;return -1;}
 if(input.failRequest==3&&request==EVIOCGBIT(EV_REL, sizeof(unsigned long))){errno=EACCES;return -1;}
 if(request==EVIOCGID){auto id=static_cast<input_id*>(out);id->bustype=input.bus;id->vendor=123;id->product=456;return 0;}
 if(_IOC_NR(request)==_IOC_NR(EVIOCGNAME(1))){std::strcpy(static_cast<char*>(out),"Fake scanner keyboard");return 22;}
 if(request==EVIOCGBIT(0,sizeof(unsigned long))){set(out,EV_KEY);if(input.relative||input.mouse)set(out,EV_REL);if(input.touch)set(out,EV_ABS);return sizeof(unsigned long);}
 if(_IOC_NR(request)==_IOC_NR(EVIOCGBIT(EV_KEY,1))){if(input.keyboard){set(out,KEY_Q);set(out,KEY_A);set(out,KEY_SPACE);}if(input.mouse)set(out,BTN_LEFT);if(input.touch)set(out,BTN_TOUCH);return _IOC_SIZE(request);}
 if(_IOC_NR(request)==_IOC_NR(EVIOCGBIT(EV_REL,1))){if(input.mouse){set(out,REL_X);set(out,REL_Y);}return _IOC_SIZE(request);}
 if(_IOC_NR(request)==_IOC_NR(EVIOCGBIT(EV_ABS,1))){if(input.touch)set(out,ABS_MT_POSITION_X);return _IOC_SIZE(request);}
 errno=EINVAL;return -1;
}
int query(int,unsigned long request,int enable) {ioctlCalls++;if(request==EVIOCGRAB){if(!enable){releaseCalls++;return 0;}if(denyGrab){errno=EBUSY;return -1;}return 0;}errno=EINVAL;return -1;}
}
#define opendir(path) fixture::openDir(path)
#define readdir(dir) fixture::nextEntry(dir)
#define closedir(dir) fixture::closeDir(dir)
#define open(path,flags) fixture::openFile(path,flags)
#define close(fd) fixture::closeFile(fd)
#define stat(path,result) fixture::statFile(path,result)
#define fstat(fd,result) fixture::statFD(fd,result)
#define read(fd,out,size) fixture::readFile(fd,out,size)
#define poll(fds,count,timeout) fixture::pollFD(fds,count,timeout)
#define setpriority(which,who,value) fixture::setPriority(which,who,value)
#define getpriority(which,who) fixture::getPriority(which,who)
#define MAPPER_INPUT_TEST_MANUAL_CAPTURE
#define ioctl(fd,request,arg) fixture::query(fd,request,arg)
#include "../../app/src/main/cpp/input_reader.cpp"
#undef opendir
#undef readdir
#undef closedir
#undef open
#undef close
#undef stat
#undef fstat
#undef read
#undef ioctl
#undef setpriority
#undef getpriority
#undef poll

void require(bool value,const char* message){if(!value){std::fprintf(stderr,"FAIL %s\n",message);std::exit(1);}}
void reset() {
 stopCaptureWorker();
 manualCapture=true;captureStop=false;pendingEvents.clear();captureAlive=false;capturePriorityApplied=false;capturePriorityError=0;
 queueOverflows=coalescedMotions=maxQueueDepth=rawEvents=rawReadCalls=lastCaptureGapMaxUs=0;fixture::priorityDenied=false;
 devices.clear();inventory.clear();lastError.clear();scanError.clear();topologyError.clear();grabbing=false;topologyLost=false;
 fixture::inputs.clear();fixture::denyDirectory=fixture::denyOpen=fixture::denyGrab=false;
 fixture::openCalls=fixture::dirCalls=fixture::ioctlCalls=fixture::releaseCalls=0;
 nextPollDevice=0;budgetYields=syncDrops=disconnects=readErrors=staleBudgetYields=0;lastFault.clear();
 fixture::infinite=false;fixture::readEOF=false;fixture::pollError=0;
 for(size_t i=0;i<70;i++){fixture::streams[i].clear();fixture::offsets[i]=0;fixture::readCalls[i]=fixture::readErr[i]=fixture::forcedRevents[i]=0;}
 lastScan=std::chrono::steady_clock::now();
}
bool enable(){return Java_cn_offline_mapper_engine_NativeInput_setGrab(nullptr,nullptr,JNI_TRUE);}
void disable(){Java_cn_offline_mapper_engine_NativeInput_setGrab(nullptr,nullptr,JNI_FALSE);}
void enqueue(size_t index,int type,int code,int value) {
 input_event event{};event.type=type;event.code=code;event.value=value;fixture::streams[index].push_back(event);
}
int main(){
 reset();fixture::denyDirectory=true;require(!enable(),"directory denial fails start");require(lastError.find("Permission denied")!=std::string::npos,"directory errno survives start");require(!directoryReadable,"directory unreadable flag");std::puts("PASS directory denial retains the actual scan error");
 reset();fixture::inputs.emplace_back();fixture::denyOpen=true;require(!enable(),"node open denial fails start");require(inventory[0].readError.find("open:")!=std::string::npos,"open denial reason");require(lastError.find("Permission denied")!=std::string::npos,"open denial retained");std::puts("PASS node open denial is distinguished from missing devices");
 for(int failure:{1,2}){reset();fixture::inputs.emplace_back();fixture::inputs[0].failRequest=failure;require(!enable(),"query failure must not become accepted");require(!inventory[0].readError.empty()&&!scanError.empty(),"query error retained");}
 std::puts("PASS identity and capability ioctl failures are explicit");
 reset();fixture::inputs.emplace_back();fixture::inputs[0].mouse=true;require(enable(),"USB composite accepted");require(devices.size()==1&&inventory[0].keyboard&&inventory[0].mouse,"composite capabilities retained");int dirs=fixture::dirCalls;disable();require(fixture::dirCalls==dirs&&fixture::releaseCalls==1,"pause releases without scanning");std::puts("PASS USB composite and pause without rediscovery");
 reset();fixture::inputs.emplace_back();fixture::inputs[0].bus=BUS_BLUETOOTH;require(enable(),"BT keyboard accepted");disable();
 reset();fixture::inputs.emplace_back();fixture::inputs[0].bus=BUS_HOST;require(!enable(),"internal keyboard protected");require(inventory[0].reason=="总线不是USB或蓝牙","bus rejection reason");
 reset();fixture::inputs.emplace_back();fixture::inputs[0].touch=true;require(!enable(),"touch node protected");require(inventory[0].touch,"touch diagnostic flag");
 reset();fixture::inputs.emplace_back();fixture::inputs[0].keyboard=false;require(!enable(),"power only node rejected");
 std::puts("PASS Bluetooth inclusion and internal/touch/power exclusions");
 reset();fixture::inputs.emplace_back();fixture::inputs[0].failRequest=3;require(enable(),"do not query relative capability on keyboard without EV_REL");disable();std::puts("PASS only advertised capability types are queried");
 reset();fixture::inputs.emplace_back();fixture::denyGrab=true;require(!enable(),"busy grab fails start");require(lastError.find("busy")!=std::string::npos&&!inventory[0].grabError.empty(),"busy failure separate from recognition");require(inventory[0].accepted,"busy node remains recognized");std::puts("PASS EVIOCGRAB busy is retained as an acquisition failure");
 reset();fixture::inputs.emplace_back();scan(true);int operations=fixture::openCalls+fixture::ioctlCalls;auto json=diagnostics();require(operations==fixture::openCalls+fixture::ioctlCalls,"diagnostics must be cached");require(json.find("\"acceptedCount\":1")!=std::string::npos,"cached diagnostics count");std::puts("PASS diagnostic reads perform no input operations");
 reset();fixture::inputs.resize(70);scan(true);require(nodeCount==70&&devices.size()==64&&inventory.size()==64,"bounded inventory and fd count");require(diagnostics().find("\"truncated\":true")!=std::string::npos&&diagnostics().size()<25000,"bounded serialized diagnostics");std::puts("PASS node and serialized diagnostic bounds");
 reset();fixture::inputs.resize(2);require(enable(),"two devices start");
 for(int i=0;i<5000;i++)enqueue(0,EV_REL,REL_X,i);
 enqueue(1,EV_KEY,KEY_SPACE,1);enqueue(1,EV_KEY,KEY_SPACE,0);
 auto first=pollEvents(0);require(first.size()/4==66,"one mouse slice and both keyboard edges in same poll");
 require(first[first.size()-1]==0&&first[first.size()-5]==1,"keyboard down/up order retained");
 require(grabbing&&devices[0].grabbed&&devices[1].grabbed,"budget keeps exclusive ownership");
 int expected=0;for(size_t i=0;i<first.size();i+=4)if(first[i]==devices[0].id)require(first[i+3]==expected++,"first mouse slice order");
 while(fixture::offsets[0]<fixture::streams[0].size()){
  auto batch=pollEvents(0);require(batch.size()<=4096,"JNI size bound");
  for(size_t i=0;i<batch.size();i+=4)if(batch[i]==devices[0].id)require(batch[i+3]==expected++,"backlogged motion order retained");
 }
 require(expected==5000&&grabbing&&budgetYields>0&&lastFault.empty(),"large backlog drains without false disconnect");
 std::puts("PASS 5000 high-rate events preserve order across bounded polls and keyboard remains responsive");
 reset();fixture::inputs.resize(20);require(enable(),"many devices start");
 for(size_t d=0;d<20;d++)for(int i=0;i<500;i++)enqueue(d,EV_KEY,KEY_SPACE,i%2);
 auto crowded=pollEvents(0);require(crowded.size()==4096,"global budget caps native/JNI batch");
 require(fixture::offsets[16]==0&&fixture::offsets[19]==0,"global budget leaves late devices queued");
 pollEvents(0);require(fixture::offsets[16]>0&&fixture::offsets[19]>0,"round robin rotates start for late devices");
 std::puts("PASS global budget and rotating fairness across twenty ready devices");
 reset();fixture::inputs.emplace_back();require(enable(),"idle start");int reads=fixture::readCalls[0];
 require(pollEvents(0).empty()&&reads==fixture::readCalls[0],"no read without readiness");
 fixture::infinite=true;auto bounded=pollEvents(0);require(bounded.empty()&&rawEvents==64&&grabbing,"continuous producer cannot monopolize poll");
 require(fixture::readCalls[0]-reads==1,"per device reads are bounded");
 disable();require(enable()&&staleBudgetYields==1,"activation stale drain terminates for continuous producer");
 std::puts("PASS idle poll and continuous-producer bounds including stale activation drain");
 reset();fixture::inputs.emplace_back();require(enable(),"overflow fixture start");
 enqueue(0,EV_SYN,SYN_DROPPED,0);enqueue(0,EV_SYN,SYN_REPORT,0);enqueue(0,EV_KEY,KEY_SPACE,1);
 auto dropped=pollEvents(0);require(dropped==std::vector<int>({-1,-1,0,0})&&!grabbing,"real kernel loss pauses");
 require(syncDrops==1&&lastError.find("event0")!=std::string::npos&&lastError.find("SYN_DROPPED")!=std::string::npos,"overflow diagnosis precise");
 require(diagnostics().find("\"syncDrops\":1")!=std::string::npos,"overflow metadata retained");
 std::puts("PASS genuine SYN_DROPPED retains safe pause and explicit path/counter");
 reset();fixture::inputs.emplace_back();require(enable(),"HUP fixture start");fixture::forcedRevents[0]=POLLHUP;
 require(pollEvents(0)==std::vector<int>({-1,-1,0,0})&&!grabbing&&devices.empty(),"HUP closes and pauses");
 require(disconnects==1&&lastError.find("revents=")!=std::string::npos&&lastError.find("event0")!=std::string::npos,"HUP explicit");
 reset();fixture::inputs.emplace_back();require(enable(),"EOF fixture start");fixture::readEOF=true;
 require(pollEvents(0)==std::vector<int>({-1,-1,0,0})&&!grabbing&&devices.empty()&&lastError.find("EOF")!=std::string::npos,"EOF explicitly closes and pauses");
 reset();fixture::inputs.emplace_back();require(enable(),"read error fixture start");fixture::readErr[0]=EIO;
 require(pollEvents(0)==std::vector<int>({-1,-1,0,0})&&!grabbing,"hard read error pauses");
 require(readErrors==1&&lastError.find("read 失败")!=std::string::npos&&lastError.find("event0")!=std::string::npos,"read error explicit");
 reset();fixture::inputs.emplace_back();require(enable(),"EINTR fixture start");fixture::readErr[0]=EINTR;
 require(pollEvents(0).empty()&&grabbing&&fixture::readCalls[0]==5,"repeated EINTR is bounded without disconnect");
 fixture::pollError=EINTR;require(pollEvents(0).empty()&&grabbing,"poll EINTR is not disconnect");
 fixture::pollError=EIO;require(pollEvents(0)==std::vector<int>({-1,-1,0,0})&&!grabbing,"poll hard failure pauses explicitly");
 std::puts("PASS HUP, hard read/poll failures and interrupted-read behavior");
 reset();fixture::inputs.emplace_back();require(enable(),"queue fixture start");
 for(int pass=0;pass<1000;pass++){
  for(int i=0;i<16;i++){enqueue(0,EV_REL,REL_X,2);enqueue(0,EV_REL,REL_Y,-1);enqueue(0,EV_SYN,SYN_REPORT,0);}
  enqueueCaptured(pollEvents(0));
 }
 require(pendingEvents.size()==8&&pendingEvents[3]==32000&&pendingEvents[7]==-16000,"blocked consumer keeps all cumulative motion in two records");
 require(rawEvents==48000&&coalescedMotions==31998&&queueOverflows==0&&grabbing,"capture progresses without consumer calls");
 enqueue(0,EV_KEY,KEY_SPACE,1);enqueue(0,EV_REL,REL_X,7);enqueue(0,EV_KEY,KEY_SPACE,0);enqueueCaptured(pollEvents(0));
 require(pendingEvents.size()==20&&pendingEvents[9]==EV_KEY&&pendingEvents[11]==1&&pendingEvents[15]==7&&pendingEvents[19]==0,"key boundaries prevent cross-boundary motion coalescing");
 std::puts("PASS 48000 producer events with blocked consumer preserve motion and key boundaries");
 reset();fixture::inputs.emplace_back();require(enable(),"overflow queue fixture");
 std::vector<int> keys;for(size_t i=0;i<MAX_PENDING_RECORDS+1;i++)keys.insert(keys.end(),{devices[0].id,EV_KEY,KEY_SPACE,int(i%2)});
 enqueueCaptured(keys);require(!grabbing&&queueOverflows==1&&pendingEvents==std::vector<int>({-1,-1,0,0})&&lastFault.find("队列已满")!=std::string::npos,"queue full safely releases instead of silently dropping key");
 std::puts("PASS finite queue overflow reports fault and releases exclusive input");
 reset();fixture::inputs.emplace_back();require(enable(),"scan cache fixture");int openBefore=fixture::openCalls;
 lastScan=std::chrono::steady_clock::time_point{};scan(false);require(fixture::openCalls==openBefore,"unchanged directory avoids recurring capability scan");
 manualCapture=false;fixture::priorityDenied=true;startCaptureWorker();
 auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(1);bool alive=false;
 while(std::chrono::steady_clock::now()<deadline){{std::lock_guard<std::mutex> guard(lock);alive=captureAlive;if(alive)break;}std::this_thread::yield();}
 require(alive,"real thread launches with fake kernel I/O");
 {std::lock_guard<std::mutex> guard(lock);require(!capturePriorityApplied&&capturePriorityError==EACCES&&captureNice==0,"nice privilege denial is visible and not falsely claimed");}
 auto beforeStop=std::chrono::steady_clock::now();stopCaptureWorker();
 require(!captureWorker.joinable()&&!captureAlive&&!grabbing&&std::chrono::steady_clock::now()-beforeStop<std::chrono::seconds(1),"stop wakes and joins without lock inversion");
 reset();fixture::inputs.emplace_back();require(enable(),"priority success fixture");manualCapture=false;startCaptureWorker();
 deadline=std::chrono::steady_clock::now()+std::chrono::seconds(1);alive=false;
 while(std::chrono::steady_clock::now()<deadline){{std::lock_guard<std::mutex> guard(lock);alive=captureAlive;if(alive)break;}std::this_thread::yield();}
 require(alive,"second capture thread starts");
 {std::lock_guard<std::mutex> guard(lock);require(capturePriorityApplied&&capturePriorityError==0&&captureNice==-20,"successful per-thread priority metadata");}
 Java_cn_offline_mapper_engine_NativeInput_close(nullptr,nullptr);
 require(!captureWorker.joinable()&&!captureAlive&&devices.empty(),"close joins and removes descriptors");
 std::puts("PASS scan cache, real worker lifecycle and priority success/failure metadata");
 std::puts("ALL SCANNER SELF TESTS PASSED");
}
