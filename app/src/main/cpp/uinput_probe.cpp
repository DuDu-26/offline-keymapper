// Development-only command-line probe. Not linked into the APK library.
// A root test harness may create a USB-identified keyboard/mouse and feed lines:
// key <evdevCode> <0|1>, rel <axis> <delta>, quit.
#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cerrno>
int main(int argc,char** argv) {
    if(argc==3 && !strcmp(argv[1],"--check-grab")) {
        int input=open(argv[2],O_RDONLY|O_NONBLOCK|O_CLOEXEC);
        if(input<0){perror("open");return 2;}
        int result=ioctl(input,EVIOCGRAB,1),error=errno;
        if(result==0){ioctl(input,EVIOCGRAB,0);puts("FREE");}
        else if(error==EBUSY)puts("BUSY");else fprintf(stderr,"grab: %s\n",strerror(error));
        close(input);return result==0?0:error==EBUSY?0:2;
    }
    int fd=open("/dev/uinput",O_WRONLY|O_NONBLOCK|O_CLOEXEC);
    if(fd<0){perror("uinput");return 1;}
    ioctl(fd,UI_SET_EVBIT,EV_KEY);ioctl(fd,UI_SET_EVBIT,EV_REL);
    for(int code=1;code<256;code++)ioctl(fd,UI_SET_KEYBIT,code);
    ioctl(fd,UI_SET_KEYBIT,BTN_LEFT);ioctl(fd,UI_SET_KEYBIT,BTN_RIGHT);
    ioctl(fd,UI_SET_RELBIT,REL_X);ioctl(fd,UI_SET_RELBIT,REL_Y);ioctl(fd,UI_SET_RELBIT,REL_WHEEL);
    uinput_user_dev device{};
    snprintf(device.name,sizeof(device.name),"OfflineMapper Development USB Probe");
    device.id.bustype=BUS_USB;device.id.vendor=0x1209;device.id.product=0xF001;device.id.version=1;
    if(write(fd,&device,sizeof(device))!=sizeof(device)||ioctl(fd,UI_DEV_CREATE)!=0){perror("create");close(fd);return 2;}
    puts("READY");fflush(stdout);
    char line[128];
    while(fgets(line,sizeof(line),stdin)) {
        if(!strncmp(line,"quit",4))break;
        char kind[16];int code,value;
        if(sscanf(line,"%15s %d %d",kind,&code,&value)!=3)continue;
        input_event event{};event.type=!strcmp(kind,"key")?EV_KEY:EV_REL;event.code=code;event.value=value;
        if(write(fd,&event,sizeof(event))!=sizeof(event)){perror("event");break;}
        event.type=EV_SYN;event.code=SYN_REPORT;event.value=0;write(fd,&event,sizeof(event));
    }
    ioctl(fd,UI_DEV_DESTROY);close(fd);return 0;
}
