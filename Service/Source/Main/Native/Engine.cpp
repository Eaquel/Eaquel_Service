#include <jni.h>
#include <android/log.h>
#include <sys/system_properties.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/prctl.h>
#include <sys/inotify.h>
#include <sys/ptrace.h>
#include <sys/mman.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <dirent.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <ifaddrs.h>
#include <netdb.h>
#include <poll.h>
#include <sys/epoll.h>
#include <signal.h>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <cerrno>
#include <string>
#include <vector>
#include <map>
#include <set>
#include <sstream>
#include <functional>
#include <mutex>
#include <thread>
#include <atomic>
#include <chrono>
#include <memory>
#include <algorithm>
#include <numeric>
#include <fstream>
#include <iomanip>

#define TAG       "EaquelCore"
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,   TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,    TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,    TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,   TAG, __VA_ARGS__)

#define JNI_VER JNI_VERSION_1_6

static JavaVM*            g_jvm            = nullptr;
static std::atomic<bool>  g_init{false};
static std::atomic<bool>  g_scan_cancel{false};
static std::atomic<bool>  g_server_running{false};
static std::atomic<int>   g_client_count{0};
static std::atomic<int>   g_threat_level{0};

struct JniGuard {
    JNIEnv* env     = nullptr;
    bool    attached= false;
    JniGuard() {
        if (!g_jvm) return;
        int r = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VER);
        if (r == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
            else env = nullptr;
        }
    }
    ~JniGuard() { if (attached && env && g_jvm) g_jvm->DetachCurrentThread(); }
    bool ok() const { return env != nullptr; }
};

static std::string j2s(JNIEnv* e, jstring j) {
    if (!e || !j) return "";
    const char* c = e->GetStringUTFChars(j, nullptr);
    std::string r(c ? c : "");
    if (c) e->ReleaseStringUTFChars(j, c);
    return r;
}

static jstring s2j(JNIEnv* e, const std::string& s) {
    return e ? e->NewStringUTF(s.c_str()) : nullptr;
}

static std::string sysprop(const char* name, const char* def = "") {
    char val[PROP_VALUE_MAX] = {};
    int  len = __system_property_get(name, val);
    return len > 0 ? std::string(val, static_cast<size_t>(len)) : (def ? def : "");
}

static int sdk_int() {
    auto s = sysprop("ro.build.version.sdk");
    return s.empty() ? 0 : std::stoi(s);
}

static std::string primary_abi() { return sysprop("ro.product.cpu.abi"); }
static bool is_debuggable()      { return sysprop("ro.debuggable") == "1"; }
static bool has_32bit()          { return !sysprop("ro.product.cpu.abilist32").empty(); }
static bool has_64bit()          { return !sysprop("ro.product.cpu.abilist64").empty(); }

static bool file_exists(const char* path) {
    struct stat st{};
    return stat(path, &st) == 0;
}

static bool is_executable(const char* path) { return access(path, X_OK) == 0; }
static bool is_readable(const char* path)   { return access(path, R_OK) == 0; }

static bool dir_exists(const char* path) {
    struct stat st{};
    return stat(path, &st) == 0 && S_ISDIR(st.st_mode);
}

static std::string read_file(const char* path, size_t maxBytes = 8192) {
    if (!path) return "";
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return "";
    std::vector<char> buf(maxBytes + 1, 0);
    ssize_t r = read(fd, buf.data(), maxBytes);
    close(fd);
    return r > 0 ? std::string(buf.data(), static_cast<size_t>(r)) : "";
}

static std::vector<std::string> read_lines(const char* path) {
    auto content = read_file(path);
    std::vector<std::string> lines;
    std::istringstream ss(content);
    std::string line;
    while (std::getline(ss, line)) if (!line.empty()) lines.push_back(line);
    return lines;
}

static bool port_open(const char* host, int port, int timeout_ms) {
    int s = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK, 0);
    if (s < 0) return false;
    struct sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port   = htons(static_cast<uint16_t>(port));
    if (inet_pton(AF_INET, host, &addr.sin_addr) <= 0) { close(s); return false; }
    connect(s, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    bool ok = false;
    fd_set wfds; FD_ZERO(&wfds); FD_SET(s, &wfds);
    struct timeval tv { timeout_ms/1000, (timeout_ms%1000)*1000 };
    if (select(s+1, nullptr, &wfds, nullptr, &tv) > 0) {
        int err = 0; socklen_t l = sizeof(err);
        if (getsockopt(s, SOL_SOCKET, SO_ERROR, &err, &l) == 0 && err == 0) ok = true;
    }
    close(s);
    return ok;
}

static long long mono_ms() {
    struct timespec t{};
    clock_gettime(CLOCK_MONOTONIC, &t);
    return static_cast<long long>(t.tv_sec)*1000LL + t.tv_nsec/1000000LL;
}

static long long boot_ms() {
    struct timespec t{};
    clock_gettime(CLOCK_BOOTTIME, &t);
    return static_cast<long long>(t.tv_sec)*1000LL + t.tv_nsec/1000000LL;
}

static long mem_kb(const char* field) {
    auto content = read_file("/proc/meminfo");
    std::istringstream ss(content);
    std::string line;
    size_t fl = strlen(field);
    while (std::getline(ss, line)) {
        if (line.rfind(field, 0) == 0) {
            long v = 0; sscanf(line.c_str() + fl + 1, "%ld", &v); return v;
        }
    }
    return -1L;
}

static long total_mem_kb() { return mem_kb("MemTotal:");     }
static long avail_mem_kb() { return mem_kb("MemAvailable:"); }
static long free_mem_kb()  { return mem_kb("MemFree:");      }

static std::string cpu_info() {
    auto c = read_file("/proc/cpuinfo", 16384);
    std::istringstream ss(c); std::string line;
    while (std::getline(ss, line))
        if (line.rfind("Hardware",0)==0 || line.rfind("model name",0)==0) return line;
    return sysprop("ro.hardware");
}

static int cpu_count() { return static_cast<int>(sysconf(_SC_NPROCESSORS_CONF)); }

static std::string selinux_ctx() {
    auto c = read_file("/proc/self/attr/current");
    while (!c.empty() && (c.back()=='\n'||c.back()=='\0')) c.pop_back();
    return c;
}

static bool selinux_enforcing() {
    auto v = read_file("/sys/fs/selinux/enforce");
    return !v.empty() && v[0]=='1';
}

static std::string read_proc_cmdline(pid_t pid) {
    std::string path = "/proc/" + std::to_string(pid) + "/cmdline";
    auto c = read_file(path.c_str());
    for (char& ch : c) if (ch=='\0') ch=' ';
    return c;
}

static std::vector<pid_t> find_procs_by_name(const char* name) {
    std::vector<pid_t> result;
    DIR* d = opendir("/proc"); if (!d) return result;
    dirent* e;
    while ((e = readdir(d)) != nullptr) {
        if (e->d_type != DT_DIR) continue;
        pid_t pid = static_cast<pid_t>(atoi(e->d_name));
        if (pid <= 0) continue;
        if (read_proc_cmdline(pid).find(name) != std::string::npos) result.push_back(pid);
    }
    closedir(d);
    return result;
}

struct MemRegion {
    uintptr_t   start = 0, end = 0;
    size_t      size  = 0;
    std::string perms, path;
};

static std::vector<MemRegion> parse_maps() {
    std::vector<MemRegion> regions;
    for (auto& line : read_lines("/proc/self/maps")) {
        MemRegion r{};
        char perms[8]{}, name[256]{};
        unsigned long start, end;
        if (sscanf(line.c_str(), "%lx-%lx %7s %*s %*s %*s %255[^\n]",
                   &start, &end, perms, name) >= 3) {
            r.start = static_cast<uintptr_t>(start);
            r.end   = static_cast<uintptr_t>(end);
            r.perms = perms; r.path = name;
            r.size  = r.end - r.start;
            regions.push_back(r);
        }
    }
    return regions;
}

static std::string build_stats_json() {
    std::ostringstream o;
    o << "{"
      << "\"sdk\":"          << sdk_int()                             << ","
      << "\"abi\":\""        << primary_abi()                         << "\","
      << "\"has32\":"        << (has_32bit()     ? "true":"false")    << ","
      << "\"has64\":"        << (has_64bit()     ? "true":"false")    << ","
      << "\"debuggable\":"   << (is_debuggable() ? "true":"false")    << ","
      << "\"selinux\":\""    << selinux_ctx()                         << "\","
      << "\"selinux_enf\":"  << (selinux_enforcing() ? "true":"false")<< ","
      << "\"totalMemKb\":"   << total_mem_kb()                        << ","
      << "\"availMemKb\":"   << avail_mem_kb()                        << ","
      << "\"freeMemKb\":"    << free_mem_kb()                         << ","
      << "\"bootMs\":"       << boot_ms()                             << ","
      << "\"monoMs\":"       << mono_ms()                             << ","
      << "\"cpuCount\":"     << cpu_count()                           << ","
      << "\"pid\":"          << getpid()                              << ","
      << "\"uid\":"          << getuid()                              << ","
      << "\"server\":"       << (g_server_running.load() ? "true":"false") << ","
      << "\"clients\":"      << g_client_count.load()                 << ","
      << "\"threatLevel\":"  << g_threat_level.load()
      << "}";
    return o.str();
}

static bool is_private_ipv4(uint32_t ip) {
    uint8_t  a  = (ip >> 24) & 0xFF;
    uint16_t ab = (ip >> 16) & 0xFFFF;
    return (a == 10) || (a == 172 && ((ip>>20)&0xF)==1) || (ab == 0xC0A8);
}

static std::string native_get_wifi_ip() {
    char prop[PROP_VALUE_MAX]{};
    const char* props[] = {"dhcp.wlan0.ipaddress","net.wlan0.ipv4","dhcp.wlan1.ipaddress","net.wlan1.ipv4",nullptr};
    for (const char** p = props; *p; ++p)
        if (__system_property_get(*p, prop) > 0 && strlen(prop) > 5) return std::string(prop);

    struct ifaddrs* list = nullptr;
    if (getifaddrs(&list) != 0) return "";
    std::string result;

    const char* exact[] = {"wlan0","wlan1","swlan0","wlan2","ap0","p2p-wlan0-0",nullptr};
    for (const char** n = exact; *n && result.empty(); ++n) {
        for (struct ifaddrs* ifa = list; ifa; ifa = ifa->ifa_next) {
            if (!ifa->ifa_name || !ifa->ifa_addr || ifa->ifa_addr->sa_family != AF_INET) continue;
            if (strcmp(ifa->ifa_name, *n) != 0) continue;
            auto* sin = reinterpret_cast<struct sockaddr_in*>(ifa->ifa_addr);
            uint32_t ip = ntohl(sin->sin_addr.s_addr);
            if (!is_private_ipv4(ip)) continue;
            char buf[INET_ADDRSTRLEN]{};
            if (inet_ntop(AF_INET, &sin->sin_addr, buf, sizeof(buf))) { result = buf; break; }
        }
    }
    if (result.empty()) {
        const char* pfx[] = {"wlan","swlan","rmnet_wlan","p2p0",nullptr};
        for (const char** p = pfx; *p && result.empty(); ++p) {
            for (struct ifaddrs* ifa = list; ifa; ifa = ifa->ifa_next) {
                if (!ifa->ifa_name || !ifa->ifa_addr || ifa->ifa_addr->sa_family != AF_INET) continue;
                if (strncmp(ifa->ifa_name, *p, strlen(*p)) != 0) continue;
                auto* sin = reinterpret_cast<struct sockaddr_in*>(ifa->ifa_addr);
                uint32_t ip = ntohl(sin->sin_addr.s_addr);
                if (!is_private_ipv4(ip)) continue;
                char buf[INET_ADDRSTRLEN]{};
                if (inet_ntop(AF_INET, &sin->sin_addr, buf, sizeof(buf))) { result = buf; break; }
            }
        }
    }
    freeifaddrs(list);
    return result;
}

static const uint8_t ADB_CNXN_MSG[] = {
    0x43,0x4e,0x58,0x4e,0x01,0x00,0x00,0x01,0x00,0x00,0x04,0x00,
    0x0e,0x00,0x00,0x00,0x15,0x3c,0x00,0x00,0xbc,0xff,0xff,0xfe,
    0x68,0x6f,0x73,0x74,0x3a,0x3a,0x00
};
static const uint32_t ADB_CMD_CNXN = 0x4e584e43u;
static const uint32_t ADB_CMD_AUTH = 0x48545541u;
static const uint32_t ADB_CMD_STLS = 0x534c5453u;

#if defined(__arm__) || defined(__i386__)
  #define SCAN_CHUNK 128
#else
  #define SCAN_CHUNK 256
#endif

#define EPOLL_ENCODE(fd,port) ((uint64_t)((uint32_t)(fd))|((uint64_t)((uint32_t)(port))<<32))
#define EPOLL_FD(u)           ((int)((uint32_t)((u)&0xFFFFFFFFULL)))
#define EPOLL_PORT(u)         ((int)((uint32_t)((u)>>32)))

static bool verify_adb_port(const struct sockaddr_in& addr, int timeout_ms) {
    int fd = socket(AF_INET, SOCK_STREAM|SOCK_NONBLOCK|SOCK_CLOEXEC, 0);
    if (fd < 0) return false;
    int opt=1; setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &opt, sizeof(opt));
    bool found = false;
    connect(fd, reinterpret_cast<const sockaddr*>(&addr), sizeof(addr));
    struct pollfd pfd{fd, POLLOUT, 0};
    if (poll(&pfd, 1, timeout_ms) > 0 && (pfd.revents & POLLOUT)) {
        int err=0; socklen_t el=sizeof(err);
        getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &el);
        if (err == 0) {
            ssize_t sent = send(fd, ADB_CNXN_MSG, sizeof(ADB_CNXN_MSG), MSG_NOSIGNAL);
            if (sent == static_cast<ssize_t>(sizeof(ADB_CNXN_MSG))) {
                struct pollfd rpfd{fd, POLLIN, 0};
                if (poll(&rpfd, 1, timeout_ms) > 0 && (rpfd.revents & POLLIN)) {
                    uint8_t hdr[24]{};
                    if (recv(fd, hdr, sizeof(hdr), MSG_WAITALL|MSG_NOSIGNAL) >= 4) {
                        uint32_t cmd; memcpy(&cmd, hdr, 4);
                        found = (cmd==ADB_CMD_CNXN || cmd==ADB_CMD_AUTH || cmd==ADB_CMD_STLS);
                    }
                }
            }
        }
    }
    close(fd); return found;
}

static int native_get_adb_port_from_props() {
    const char* keys[] = {"service.adb.tcp.port","persist.adb.tcp.port","persist.sys.adb.tcp.port","service.adb.tcp.port2",nullptr};
    char val[PROP_VALUE_MAX]{};
    for (const char** k = keys; *k; ++k) {
        if (__system_property_get(*k, val) > 0) {
            int p = atoi(val);
            if (p > 1024 && p < 65536) return p;
        }
    }
    return -1;
}

static int read_adb_port_from_proc_net() {
    const char* files[] = {"/proc/net/tcp6","/proc/net/tcp",nullptr};
    for (const char** fp = files; *fp; ++fp) {
        FILE* f = fopen(*fp, "r"); if (!f) continue;
        char line[512]; fgets(line, sizeof(line), f);
        while (fgets(line, sizeof(line), f)) {
            unsigned int sl, local_port, rem_port, state;
            char local_addr[64], rem_addr[64];
            if (sscanf(line," %u: %63[^:]:%x %63[^:]:%x %x",&sl,local_addr,&local_port,rem_addr,&rem_port,&state)>=6) {
                if (state==0x0A && local_port>1024 && local_port<65536 &&
                    ((local_port>=30000&&local_port<=65535) || local_port==5555||local_port==5556||local_port==5037)) {
                    struct in_addr ha{}; inet_pton(AF_INET,"127.0.0.1",&ha);
                    struct sockaddr_in addr{}; addr.sin_family=AF_INET; addr.sin_addr=ha;
                    addr.sin_port=htons(static_cast<uint16_t>(local_port));
                    if (verify_adb_port(addr, 500)) { fclose(f); return (int)local_port; }
                }
            }
        }
        fclose(f);
    }
    return -1;
}

static int native_scan_adb_port(const char* host, int timeout_ms) {
    if (!host||!*host) return -1;
    struct in_addr host_addr{};
    if (inet_pton(AF_INET, host, &host_addr) != 1) return -1;

    { int p = read_adb_port_from_proc_net(); if (p > 0) return p; }
    { int p = native_get_adb_port_from_props();
      if (p > 0) {
          struct sockaddr_in a{}; a.sin_family=AF_INET; a.sin_addr=host_addr; a.sin_port=htons((uint16_t)p);
          if (verify_adb_port(a, timeout_ms)) return p;
      }
    }
    for (int kp : {5555, 5556, 5037}) {
        struct sockaddr_in a{}; a.sin_family=AF_INET; a.sin_addr=host_addr; a.sin_port=htons((uint16_t)kp);
        if (verify_adb_port(a, timeout_ms)) return kp;
    }
    const struct { int f, t; } ranges[] = {{37000,40000},{33000,37000},{40000,50000},{30000,33000},{50000,65535},{10000,30000},{5001,9999},{0,0}};
    for (int ri = 0; ranges[ri].f; ++ri) {
        for (int base = ranges[ri].f; base <= ranges[ri].t; base += SCAN_CHUNK) {
            if (g_scan_cancel.load()) return -1;
            int end  = std::min(base + SCAN_CHUNK, ranges[ri].t + 1);
            int epfd = epoll_create1(EPOLL_CLOEXEC);
            struct FdPort { int fd, port; };
            std::vector<FdPort> fds; fds.reserve(end-base);
            for (int p = base; p < end; ++p) {
                int fd = socket(AF_INET, SOCK_STREAM|SOCK_NONBLOCK|SOCK_CLOEXEC, 0); if (fd<0) continue;
                int o=1; setsockopt(fd,IPPROTO_TCP,TCP_NODELAY,&o,sizeof(o));
                struct sockaddr_in a{}; a.sin_family=AF_INET; a.sin_addr=host_addr; a.sin_port=htons((uint16_t)p);
                connect(fd, reinterpret_cast<const sockaddr*>(&a), sizeof(a));
                if (epfd >= 0) {
                    struct epoll_event ev{EPOLLOUT|EPOLLERR|EPOLLHUP,{.u64=EPOLL_ENCODE(fd,p)}};
                    if (epoll_ctl(epfd,EPOLL_CTL_ADD,fd,&ev)==0) { fds.push_back({fd,p}); continue; }
                }
                close(fd);
            }
            struct epoll_event events[SCAN_CHUNK];
            int found_port = -1;
            auto deadline  = mono_ms() + timeout_ms + 200;
            while (found_port<0 && mono_ms()<deadline) {
                int n = epoll_wait(epfd, events, SCAN_CHUNK, (int)std::max(0LL,deadline-mono_ms()));
                for (int i=0; i<n && found_port<0; ++i) {
                    int fd   = EPOLL_FD(events[i].data.u64);
                    int port = EPOLL_PORT(events[i].data.u64);
                    if (events[i].events & EPOLLOUT) {
                        int err=0; socklen_t el=sizeof(err);
                        getsockopt(fd,SOL_SOCKET,SO_ERROR,&err,&el);
                        if (err==0) {
                            ssize_t sent=send(fd,ADB_CNXN_MSG,sizeof(ADB_CNXN_MSG),MSG_NOSIGNAL);
                            if (sent==static_cast<ssize_t>(sizeof(ADB_CNXN_MSG))) {
                                struct epoll_event rev{EPOLLIN|EPOLLERR,{.u64=events[i].data.u64}};
                                epoll_ctl(epfd,EPOLL_CTL_MOD,fd,&rev);
                            }
                        }
                    } else if (events[i].events & EPOLLIN) {
                        uint8_t hdr[24]{}; ssize_t nr=recv(fd,hdr,sizeof(hdr),MSG_NOSIGNAL);
                        if (nr>=4) { uint32_t cmd; memcpy(&cmd,hdr,4); if(cmd==ADB_CMD_CNXN||cmd==ADB_CMD_AUTH||cmd==ADB_CMD_STLS) found_port=port; }
                    }
                }
            }
            for (auto& fp : fds) close(fp.fd);
            if (epfd>=0) close(epfd);
            if (found_port > 0) return found_port;
        }
    }
    return -1;
}

static int scan_pairing_port(const char* host, int timeout_ms) {
    if (!host||!*host) return -1;
    struct in_addr ha{};
    if (inet_pton(AF_INET, host, &ha) != 1) return -1;
    char val[128]{};
    for (const char* k : {"service.adb.tls.port","persist.adb.tls.port"}) {
        if (__system_property_get(k, val) > 0) {
            int p = atoi(val);
            if (p > 1024 && p < 65535) return p;
        }
    }
    const struct { int f, t; } ranges[] = {{37000,40000},{33000,37000},{40000,50000},{25000,33000},{50000,65534},{0,0}};
    for (int ri=0; ranges[ri].f; ++ri) {
        for (int base=ranges[ri].f; base<=ranges[ri].t; base+=SCAN_CHUNK) {
            if (g_scan_cancel.load()) return -1;
            int end  = std::min(base+SCAN_CHUNK, ranges[ri].t+1);
            int epfd = epoll_create1(EPOLL_CLOEXEC);
            struct FdPort { int fd, port; };
            std::vector<FdPort> fds; fds.reserve(end-base);
            for (int p=base; p<end; ++p) {
                int fd=socket(AF_INET,SOCK_STREAM|SOCK_NONBLOCK|SOCK_CLOEXEC,0); if(fd<0) continue;
                struct sockaddr_in a{}; a.sin_family=AF_INET; a.sin_addr=ha; a.sin_port=htons((uint16_t)p);
                connect(fd,reinterpret_cast<const sockaddr*>(&a),sizeof(a));
                if (epfd>=0) {
                    struct epoll_event ev{EPOLLIN|EPOLLOUT|EPOLLERR,{.u64=EPOLL_ENCODE(fd,p)}};
                    if (epoll_ctl(epfd,EPOLL_CTL_ADD,fd,&ev)==0) { fds.push_back({fd,p}); continue; }
                }
                close(fd);
            }
            int found=-1;
            if (epfd>=0) {
                struct epoll_event evs[SCAN_CHUNK];
                auto t0=std::chrono::steady_clock::now();
                while (found<0) {
                    auto el=(int)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-t0).count();
                    if (el>=timeout_ms+400) break;
                    int n=epoll_wait(epfd,evs,SCAN_CHUNK,timeout_ms+400-el);
                    for (int i=0; i<n&&found<0; ++i) {
                        if (!(evs[i].events&EPOLLIN)) continue;
                        int efd=EPOLL_FD(evs[i].data.u64), ep=EPOLL_PORT(evs[i].data.u64);
                        uint8_t b=0;
                        if (recv(efd,&b,1,MSG_PEEK|MSG_NOSIGNAL)==1 && b==0x16) found=ep;
                    }
                }
                for (auto& fp : fds) close(fp.fd);
                close(epfd);
            }
            if (found>0) return found;
        }
    }
    return -1;
}

static int detect_click_barrier() {
    if (!sysprop("ro.miui.ui.version.name").empty() || !sysprop("ro.miui.version.code_time").empty()) return 1;
    if (!sysprop("ro.build.version.oplusrom").empty() || !sysprop("ro.oppo.version.release").empty() || !sysprop("ro.build.oplus_version").empty()) return 2;
    if (!sysprop("ro.vivo.os.version").empty()) return 3;
    if (!sysprop("ro.build.version.realmeui").empty()) return 4;
    return 0;
}

static bool check_gesture_coord(int x, int y, int sw, int sh) {
    if (x<0||y<0) return false;
    if (sw>0&&x>=sw) return false;
    if (sh>0&&(y>=sh||y>sh-100)) return false;
    return true;
}

static const char* ROOT_BINS[] = {
    "/system/bin/su","/system/xbin/su","/system/app/SuperUser.apk",
    "/system/app/Superuser.apk","/data/local/su","/data/local/bin/su",
    "/data/local/xbin/su","/sbin/su","/su/bin/su","/system/sd/xbin/su",
    "/system/bin/.ext/su","/system/app/Magisk.apk","/data/adb/magisk",
    "/data/adb/ksud","/system/lib/libsupol.so",nullptr
};

static bool detect_root() {
    for (int i=0; ROOT_BINS[i]; ++i) if (file_exists(ROOT_BINS[i])) { LOGW("Root: %s",ROOT_BINS[i]); return true; }

    FILE* f=fopen("/proc/mounts","r"); if (f) {
        char line[1024]; bool rw=false;
        while (fgets(line,sizeof(line),f)) if(strstr(line," /system ")&&strstr(line,"rw")){rw=true;break;}
        fclose(f); if(rw) return true;
    }

    auto tags=sysprop("ro.build.tags");
    if (tags.find("test-keys")!=std::string::npos || sysprop("ro.secure")=="0") return true;

    for (const char* p : {"/data/adb/magisk.db","/sbin/.magisk","/data/adb/modules","/data/adb/ksu","/sbin/ksud"})
        if (file_exists(p)) { LOGW("Root indicator: %s",p); return true; }
    return !find_procs_by_name("su").empty();
}

static bool detect_emulator() {
    struct { const char* k; const char* v; } ep[] = {
        {"ro.hardware","goldfish"},{"ro.hardware","ranchu"},{"ro.hardware","vbox86"},
        {"ro.product.device","generic"},{"ro.kernel.qemu","1"},
        {"ro.product.model","sdk"},{"ro.product.model","Emulator"},
        {"ro.product.model","Android SDK built for x86"},{"ro.build.fingerprint","generic"},
        {nullptr,nullptr}
    };
    for (int i=0; ep[i].k; ++i) {
        auto v=sysprop(ep[i].k);
        if (v==ep[i].v || (strlen(ep[i].v)>3 && v.find(ep[i].v)!=std::string::npos)) { LOGW("Emu prop: %s=%s",ep[i].k,v.c_str()); return true; }
    }
    for (const char* p : {"/dev/socket/qemud","/dev/qemu_pipe","/sys/qemu_trace","/dev/goldfish_pipe"})
        if (file_exists(p)) { LOGW("Emu file: %s",p); return true; }
    auto maps = read_file("/proc/self/maps");
    return maps.find("libemulator")!=std::string::npos || maps.find("libgoldfish")!=std::string::npos;
}

static bool detect_debugger() {

    auto status=read_file("/proc/self/status");
    std::istringstream ss(status); std::string line;
    while (std::getline(ss,line)) {
        if (line.rfind("TracerPid:",0)==0) {
            int pid=0; sscanf(line.c_str()+10,"%d",&pid);
            if (pid!=0) { LOGW("TracerPid=%d",pid); return true; }
        }
    }

#if defined(__aarch64__) || defined(__x86_64__)
    if (ptrace(PTRACE_TRACEME,0,nullptr,nullptr)==-1) { LOGW("ptrace TRACEME failed"); return true; }
    ptrace(PTRACE_DETACH,0,nullptr,nullptr);
#endif

    for (const char* p : {"/data/local/tmp/frida-server","/data/local/tmp/re.frida.server","/sbin/frida-server"})
        if (file_exists(p)) { LOGW("Frida: %s",p); return true; }
    return !find_procs_by_name("frida-server").empty();
}

static bool detect_hook() {

    auto maps=read_file("/proc/self/maps");
    for (const char* s : {"frida","gum-js-loop","linjector","libfrida","frida-agent","re.frida"})
        if (maps.find(s)!=std::string::npos) { LOGW("Frida maps: %s",s); return true; }

    for (int port : {27042,27043,27044,27045})
        if (port_open("127.0.0.1",port,200)) { LOGW("Frida port: %d",port); return true; }

    for (const char* p : {"/system/xposed.prop","/system/framework/XposedBridge.jar","/system/lib/libxposed_art.so",
                          "/data/adb/lspd","/data/misc/lspatch"})
        if (file_exists(p)) { LOGW("Xposed/LSPosed: %s",p); return true; }
    if (!find_procs_by_name("lspd").empty()) return true;

    for (const char* p : {"/data/data/catch_.me_.if_.you_.can_","/data/data/com.cih.gamecih","/data/data/com.cih.gamecih2"})
        if (file_exists(p)) { LOGW("GG/CE: %s",p); return true; }

    void* h=dlopen("libc.so",RTLD_NOW); if(h){
        uint8_t* ptr=reinterpret_cast<uint8_t*>(dlsym(h,"open")); dlclose(h);
        if (ptr) {
#if defined(__aarch64__)
            if (ptr[0]==0xE1||ptr[0]==0x1F) { LOGW("Inline hook on open()"); return true; }
#elif defined(__arm__)
            if (ptr[0]==0xFF&&ptr[1]==0x5E) { LOGW("Inline hook on open()"); return true; }
#endif
        }
    }
    return false;
}

static bool detect_tamper() {
    auto tags=sysprop("ro.build.tags");
    if (tags.find("test-keys")!=std::string::npos || tags.find("dev-keys")!=std::string::npos) return true;
    auto bt=sysprop("ro.build.type");
    if (bt=="eng"||bt=="userdebug") return true;

    size_t rw=0; std::istringstream ss(read_file("/proc/self/maps")); std::string line;
    while(std::getline(ss,line)) if(line.find("rw-p")!=std::string::npos && line.find(".so")!=std::string::npos) ++rw;
    return rw > 10;
}

static bool detect_integrity(const char* ) {
    auto maps=read_file("/proc/self/maps");
    return maps.find("libgamecore.so")!=std::string::npos && dir_exists("/data/dalvik-cache");
}

struct ProcInfo { pid_t pid; std::string name, cmdline; int uid; long vmrss_kb; };

static ProcInfo get_proc_info(pid_t pid) {
    ProcInfo info{}; info.pid=pid;
    std::string base="/proc/"+std::to_string(pid);
    std::istringstream ss(read_file((base+"/status").c_str())); std::string line;
    while(std::getline(ss,line)){
        if(line.rfind("Name:",0)==0){info.name=line.substr(5); while(!info.name.empty()&&(info.name.front()==' '||info.name.front()=='\t'))info.name.erase(0,1); while(!info.name.empty()&&(info.name.back()=='\n'||info.name.back()==' '))info.name.pop_back();}
        if(line.rfind("Uid:",0)==0) sscanf(line.c_str()+4,"%d",&info.uid);
        if(line.rfind("VmRSS:",0)==0) sscanf(line.c_str()+6,"%ld",&info.vmrss_kb);
    }
    info.cmdline=read_proc_cmdline(pid);
    return info;
}

static std::vector<ProcInfo> scan_all_processes() {
    std::vector<ProcInfo> result;
    DIR* d=opendir("/proc"); if(!d) return result;
    dirent* e;
    while((e=readdir(d))!=nullptr){
        if(e->d_type!=DT_DIR) continue;
        pid_t pid=static_cast<pid_t>(atoi(e->d_name)); if(pid<=0) continue;
        try { result.push_back(get_proc_info(pid)); } catch(...) {}
    }
    closedir(d); return result;
}

static bool detect_suspicious_processes() {
    const char* bad[] = {"frida","gdbserver","strace","ltrace","tcpdump","objection","r2","radare","jdwp",nullptr};
    for (auto& p : scan_all_processes()) {
        std::string nl=p.name; std::transform(nl.begin(),nl.end(),nl.begin(),::tolower);
        for (int i=0; bad[i]; ++i) if(nl.find(bad[i])!=std::string::npos){LOGW("Şüpheli proc: %s",p.name.c_str());return true;}
    }
    return false;
}

static bool detect_suspicious_libs() {
    const char* sus[] = {"frida","inject","xposed","lspd","substrate","cydia","hook","patch","cheat",nullptr};
    for (auto& r : parse_maps()) {
        std::string nl=r.path; std::transform(nl.begin(),nl.end(),nl.begin(),::tolower);
        for (int i=0; sus[i]; ++i) if(nl.find(sus[i])!=std::string::npos){LOGW("Şüpheli lib: %s",r.path.c_str());return true;}
    }
    return false;
}

static bool detect_mitm_proxy() {
    for (const char* f : {"/proc/net/tcp","/proc/net/tcp6"}) {
        auto c=read_file(f);
        if(c.find("5C11")!=std::string::npos||c.find("1F90")!=std::string::npos||c.find("BB01")!=std::string::npos){LOGW("MITM indicator");return true;}
    }
    return false;
}

static bool detect_vpn() {
    auto ifaces=read_file("/proc/net/dev");
    return ifaces.find("tun0")!=std::string::npos||ifaces.find("ppp0")!=std::string::npos;
}

static bool detect_frida_gadget() {
    for (auto& r : parse_maps())
        if(r.path.find("frida")!=std::string::npos||r.path.find("gadget")!=std::string::npos) return true;
    return false;
}

static size_t count_mapped_libs() {
    std::set<std::string> libs;
    for (auto& r : parse_maps()) if(r.path.find(".so")!=std::string::npos) libs.insert(r.path);
    return libs.size();
}

static bool check_tee()            { return file_exists("/dev/trusty-ipc-dev0")||file_exists("/dev/tee0")||file_exists("/dev/teepriv0"); }
static bool check_verified_boot()  { auto v=sysprop("ro.boot.verifiedbootstate"); return v=="green"||v=="yellow"||sysprop("ro.boot.veritymode")=="enforcing"; }
static bool check_encryption()     { return sysprop("ro.crypto.state")=="encrypted"; }
static bool check_art_integrity()  { auto m=read_file("/proc/self/maps"); return m.find("libart.so")!=std::string::npos&&(m.find("core.vdex")!=std::string::npos||m.find("boot.art")!=std::string::npos); }
static bool check_zygote_parent()  {
    std::istringstream ss(read_file("/proc/self/status")); std::string line;
    while(std::getline(ss,line)) if(line.rfind("PPid:",0)==0){pid_t ppid=0;sscanf(line.c_str()+5,"%d",&ppid);return ppid>0&&get_proc_info(ppid).name.find("zygote")!=std::string::npos;}
    return false;
}
static bool detect_timing_attack() { auto t1=mono_ms(); volatile int s=0; for(int i=0;i<1000000;++i) s+=i; return mono_ms()-t1>500; }
static bool detect_vm_accel_miss() { auto r=sysprop("ro.hardware.egl"); return r=="swiftshader"||r=="mesa"||sysprop("ro.opengles.renderer").find("SwiftShader")!=std::string::npos; }
static bool detect_adb_always_on() { return sysprop("init.svc.adbd")=="running"&&sysprop("ro.secure")!="1"; }

static std::string full_scan_json(const char* pkg) {
    bool root=detect_root(), emu=detect_emulator(), dbg=detect_debugger(),
         hook=detect_hook(), tamper=detect_tamper(), integ=detect_integrity(pkg),
         sproc=detect_suspicious_processes(), slib=detect_suspicious_libs(), mitm=detect_mitm_proxy(),
         vboot=check_verified_boot(), enc=check_encryption(), tee=check_tee();
    int threats=(root?1:0)+(emu?1:0)+(dbg?1:0)+(hook?1:0)+(tamper?1:0)+(!integ?1:0)+(sproc?1:0)+(slib?1:0)+(mitm?1:0);
    g_threat_level.store(threats);
    std::ostringstream o;
    o<<"{"
     <<"\"root\":"<<(root?"true":"false")<<","<<"\"emulator\":"<<(emu?"true":"false")<<","
     <<"\"debugger\":"<<(dbg?"true":"false")<<","<<"\"hook\":"<<(hook?"true":"false")<<","
     <<"\"tamper\":"<<(tamper?"true":"false")<<","<<"\"integrity\":"<<(integ?"true":"false")<<","
     <<"\"suspicious_procs\":"<<(sproc?"true":"false")<<","<<"\"suspicious_libs\":"<<(slib?"true":"false")<<","
     <<"\"mitm\":"<<(mitm?"true":"false")<<","<<"\"verified_boot\":"<<(vboot?"true":"false")<<","
     <<"\"encryption\":"<<(enc?"true":"false")<<","<<"\"tee\":"<<(tee?"true":"false")<<","
     <<"\"threats\":"<<threats<<"}";
    return o.str();
}

static bool protect_self() {
    prctl(PR_SET_DUMPABLE, 0, 0, 0, 0);
    prctl(PR_SET_NAME, "eaquel_worker", 0, 0, 0);
    return true;
}

static void enable_secure_mode() { protect_self(); prctl(PR_SET_SECCOMP, 0); }

class FileSystemMonitor {
public:
    struct Event { std::string path; uint32_t mask; long long ts; };
    FileSystemMonitor() : fd_(-1), running_(false) {}
    ~FileSystemMonitor() { stop(); }

    bool start(const std::vector<std::string>& paths) {
        fd_ = inotify_init1(IN_CLOEXEC|IN_NONBLOCK);
        if (fd_<0) return false;
        for (auto& p : paths) {
            int wd=inotify_add_watch(fd_,p.c_str(),IN_CREATE|IN_DELETE|IN_MODIFY|IN_ATTRIB|IN_MOVED_FROM|IN_MOVED_TO);
            if (wd>=0) watch_map_[wd]=p;
        }
        running_.store(true); thread_=std::thread([this]{run();}); return true;
    }
    void stop() { running_.store(false); if(fd_>=0){close(fd_);fd_=-1;} if(thread_.joinable())thread_.join(); }
    std::vector<Event> getEvents() { std::lock_guard<std::mutex> l(mutex_); return std::move(events_); }

private:
    int fd_; std::atomic<bool> running_; std::thread thread_; std::mutex mutex_;
    std::map<int,std::string> watch_map_; std::vector<Event> events_;
    void run() {
        char buf[4096];
        while (running_.load()) {
            ssize_t n=read(fd_,buf,sizeof(buf)); if(n<0){usleep(100000);continue;}
            size_t pos=0;
            while (pos<static_cast<size_t>(n)) {
                auto* ev=reinterpret_cast<inotify_event*>(buf+pos);
                if (ev->wd>=0&&watch_map_.count(ev->wd)) {
                    std::string path=watch_map_[ev->wd]; if(ev->len>0) path+="/"+std::string(ev->name);
                    std::lock_guard<std::mutex> l(mutex_); events_.push_back({path,ev->mask,mono_ms()});
                    if(events_.size()>1000) events_.erase(events_.begin());
                }
                pos+=sizeof(inotify_event)+ev->len;
            }
        }
    }
};

static std::unique_ptr<FileSystemMonitor> g_fs_monitor;

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm; g_init.store(true);
    LOGI("JNI_OnLoad — SDK %d ABI %s", sdk_int(), primary_abi().c_str());
    return JNI_VER;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    g_init.store(false); g_jvm = nullptr; LOGI("JNI_OnUnload");
}

JNIEXPORT jstring JNICALL Java_com_eaquel_service_GameAdbBridge_nativeGetWifiIp(JNIEnv* e, jobject)                    { return s2j(e, native_get_wifi_ip()); }
JNIEXPORT jint    JNICALL Java_com_eaquel_service_GameAdbBridge_nativeGetAdbPort(JNIEnv*, jobject)                      { return (jint)native_get_adb_port_from_props(); }
JNIEXPORT void    JNICALL Java_com_eaquel_service_GameAdbBridge_nativeCancelScan(JNIEnv*, jobject)                      { g_scan_cancel.store(true); }
JNIEXPORT jint    JNICALL Java_com_eaquel_service_GameAdbBridge_nativeDetectClickBarrier(JNIEnv*, jobject)              { return (jint)detect_click_barrier(); }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAdbBridge_nativeCheckGestureCoord(JNIEnv*, jobject, jint x, jint y, jint sw, jint sh) { return check_gesture_coord(x,y,sw,sh)?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAdbBridge_nativePortOpen(JNIEnv* e, jobject, jstring jh, jint jp, jint tms)           { return port_open(j2s(e,jh).c_str(),(int)jp,(int)tms)?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jstring  JNICALL Java_com_eaquel_service_GameAdbBridge_nativeStats(JNIEnv* e, jobject)                        { return s2j(e, build_stats_json()); }
JNIEXPORT jstring  JNICALL Java_com_eaquel_service_GameAdbBridge_nativeSelinux(JNIEnv* e, jobject)                      { return s2j(e, selinux_ctx()); }
JNIEXPORT jstring  JNICALL Java_com_eaquel_service_GameAdbBridge_nativeAbi(JNIEnv* e, jobject)                          { return s2j(e, primary_abi()); }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAdbBridge_nativeHas32(JNIEnv*, jobject)                          { return has_32bit()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAdbBridge_nativeHas64(JNIEnv*, jobject)                          { return has_64bit()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jlong    JNICALL Java_com_eaquel_service_GameAdbBridge_nativeMonoMs(JNIEnv*, jobject)                          { return (jlong)mono_ms(); }
JNIEXPORT jlong    JNICALL Java_com_eaquel_service_GameAdbBridge_nativeBootMs(JNIEnv*, jobject)                          { return (jlong)boot_ms(); }
JNIEXPORT jlong    JNICALL Java_com_eaquel_service_GameAdbBridge_nativeTotalMemKb(JNIEnv*, jobject)                      { return (jlong)total_mem_kb(); }
JNIEXPORT jlong    JNICALL Java_com_eaquel_service_GameAdbBridge_nativeAvailMemKb(JNIEnv*, jobject)                      { return (jlong)avail_mem_kb(); }
JNIEXPORT jlong    JNICALL Java_com_eaquel_service_GameAdbBridge_nativeFreeMemKb(JNIEnv*, jobject)                       { return (jlong)free_mem_kb(); }
JNIEXPORT jstring  JNICALL Java_com_eaquel_service_GameAdbBridge_nativeCpuInfo(JNIEnv* e, jobject)                      { return s2j(e, cpu_info()); }
JNIEXPORT jint     JNICALL Java_com_eaquel_service_GameAdbBridge_nativeCpuCount(JNIEnv*, jobject)                        { return (jint)cpu_count(); }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAdbBridge_nativeSelinuxEnforcing(JNIEnv*, jobject)                { return selinux_enforcing()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAdbBridge_nativeFileExists(JNIEnv* e, jobject, jstring jp)        { return file_exists(j2s(e,jp).c_str())?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAdbBridge_nativeDirExists(JNIEnv* e, jobject, jstring jp)         { return dir_exists(j2s(e,jp).c_str())?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAdbBridge_nativeIsExec(JNIEnv* e, jobject, jstring jp)            { return is_executable(j2s(e,jp).c_str())?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAdbBridge_nativeIsReadable(JNIEnv* e, jobject, jstring jp)        { return is_readable(j2s(e,jp).c_str())?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jstring  JNICALL Java_com_eaquel_service_GameAdbBridge_nativeReadFile(JNIEnv* e, jobject, jstring jp, jint mb) { return s2j(e, read_file(j2s(e,jp).c_str(),(size_t)mb)); }
JNIEXPORT jint     JNICALL Java_com_eaquel_service_GameAdbBridge_nativeGetUid(JNIEnv*, jobject)                          { return (jint)getuid(); }
JNIEXPORT jint     JNICALL Java_com_eaquel_service_GameAdbBridge_nativeGetPid(JNIEnv*, jobject)                          { return (jint)getpid(); }
JNIEXPORT jint     JNICALL Java_com_eaquel_service_GameAdbBridge_nativeGetSdk(JNIEnv*, jobject)                          { return (jint)sdk_int(); }
JNIEXPORT void     JNICALL Java_com_eaquel_service_GameAdbBridge_nativeSetRunning(JNIEnv*, jobject, jboolean r)          { g_server_running.store(r==JNI_TRUE); }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAdbBridge_nativeIsRunning(JNIEnv*, jobject)                       { return g_server_running.load()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT void     JNICALL Java_com_eaquel_service_GameAdbBridge_nativeAddClient(JNIEnv*, jobject)                       { g_client_count.fetch_add(1); }
JNIEXPORT void     JNICALL Java_com_eaquel_service_GameAdbBridge_nativeRemoveClient(JNIEnv*, jobject)                    { if(g_client_count.fetch_sub(1)<=0) g_client_count.store(0); }
JNIEXPORT jint     JNICALL Java_com_eaquel_service_GameAdbBridge_nativeClientCount(JNIEnv*, jobject)                     { return (jint)g_client_count.load(); }
JNIEXPORT void     JNICALL Java_com_eaquel_service_GameAdbBridge_nativeEnableSecureMode(JNIEnv*, jobject)                { enable_secure_mode(); }

JNIEXPORT jint JNICALL Java_com_eaquel_service_GameAdbBridge_nativeScanAdbPort(JNIEnv* e, jobject, jstring jh, jint jt) {
    g_scan_cancel.store(false);
    auto host = j2s(e, jh); int tms = (int)jt > 0 ? (int)jt : 300;
    { int p=read_adb_port_from_proc_net(); if(p>0) return p; }
    { int p=native_get_adb_port_from_props(); if(p>0) return p; }
    return (jint)native_scan_adb_port(host.c_str(), tms);
}

JNIEXPORT jint JNICALL Java_com_eaquel_service_GameAdbBridge_nativeScanPairingPort(JNIEnv* e, jobject, jstring jh, jint jt) {
    g_scan_cancel.store(false);
    return (jint)scan_pairing_port(j2s(e,jh).c_str(), (int)jt>0?(int)jt:400);
}

JNIEXPORT jstring JNICALL Java_com_eaquel_service_GameAdbBridge_nativePair(JNIEnv* e, jobject, jstring, jint, jstring, jbyteArray) { return s2j(e,"ERR:NATIVE_UNAVAILABLE"); }
JNIEXPORT jstring JNICALL Java_com_eaquel_service_GameAdbBridge_nativeSpake2Init(JNIEnv* e, jobject, jstring)   { return s2j(e,"ERR:NATIVE_UNAVAILABLE"); }
JNIEXPORT jstring JNICALL Java_com_eaquel_service_GameAdbBridge_nativeSpake2Finish(JNIEnv* e, jobject, jstring) { return s2j(e,"ERR:NATIVE_UNAVAILABLE"); }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAdbBridge_nativeIsPairingReady(JNIEnv*, jobject)          { return JNI_FALSE; }
JNIEXPORT void     JNICALL Java_com_eaquel_service_GameAdbBridge_nativeInitPairing(JNIEnv*, jobject)             { LOGI("nativeInitPairing: Kotlin modu aktif"); }
JNIEXPORT jstring  JNICALL Java_com_eaquel_service_GameAdbBridge_nativeRunPairingTests(JNIEnv* e, jobject)       { return s2j(e,"{\"spake2\":false,\"ssl\":false,\"mode\":\"kotlin\"}"); }

JNIEXPORT jstring JNICALL Java_com_eaquel_service_GameAdbBridge_nativeListProcesses(JNIEnv* e, jobject) {
    auto procs=scan_all_processes(); std::ostringstream o; o<<"["; bool first=true;
    for (auto& p : procs) { if(!first)o<<","; o<<"{\"pid\":"<<p.pid<<",\"name\":\""<<p.name<<"\",\"uid\":"<<p.uid<<",\"rss\":"<<p.vmrss_kb<<"}"; first=false; }
    o<<"]"; return s2j(e,o.str());
}

JNIEXPORT jstring JNICALL Java_com_eaquel_service_GameAdbBridge_nativeListMaps(JNIEnv* e, jobject) {
    auto regions=parse_maps(); std::ostringstream o; o<<"["; bool first=true; int cnt=0;
    for (auto& r : regions) {
        if(cnt++>100) break;
        if(!first)o<<",";
        o<<"{\"start\":\""<<std::hex<<r.start<<"\",\"end\":\""<<r.end<<"\",\"perms\":\""<<r.perms<<"\",\"name\":\""<<r.path<<"\",\"size\":"<<std::dec<<r.size<<"}";
        first=false;
    }
    o<<"]"; return s2j(e,o.str());
}

JNIEXPORT void JNICALL Java_com_eaquel_service_GameAdbBridge_nativeStartFsMonitor(JNIEnv* e, jobject, jobjectArray jp) {
    if(!e||!jp) return;
    std::vector<std::string> paths;
    jsize len=e->GetArrayLength(jp);
    for(jsize i=0;i<len;++i){auto js=reinterpret_cast<jstring>(e->GetObjectArrayElement(jp,i));if(js)paths.push_back(j2s(e,js));}
    if(!g_fs_monitor) g_fs_monitor=std::make_unique<FileSystemMonitor>();
    g_fs_monitor->start(paths); LOGI("FS monitor: %zu yol",(size_t)paths.size());
}
JNIEXPORT void JNICALL Java_com_eaquel_service_GameAdbBridge_nativeStopFsMonitor(JNIEnv*, jobject) { if(g_fs_monitor){g_fs_monitor->stop();g_fs_monitor.reset();} }
JNIEXPORT jstring JNICALL Java_com_eaquel_service_GameAdbBridge_nativeGetFsEvents(JNIEnv* e, jobject) {
    if(!g_fs_monitor) return s2j(e,"[]");
    auto events=g_fs_monitor->getEvents(); std::ostringstream o; o<<"["; bool first=true;
    for(auto& ev:events){if(!first)o<<",";o<<"{\"path\":\""<<ev.path<<"\",\"mask\":"<<ev.mask<<",\"ts\":"<<ev.ts<<"}";first=false;}
    o<<"]"; return s2j(e,o.str());
}

JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeCheckRoot(JNIEnv*, jobject)          { return detect_root()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeCheckEmulator(JNIEnv*, jobject)      { return detect_emulator()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeCheckDebugger(JNIEnv*, jobject)      { return detect_debugger()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeCheckHook(JNIEnv*, jobject)          { return detect_hook()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeCheckTamper(JNIEnv*, jobject)        { return detect_tamper()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeCheckIntegrity(JNIEnv* e, jobject, jstring jp) { return detect_integrity(j2s(e,jp).c_str())?JNI_TRUE:JNI_FALSE; }
JNIEXPORT void     JNICALL Java_com_eaquel_service_GameAnticheat_nativeSetThreatLevel(JNIEnv*, jobject, jint lvl) { g_threat_level.store((int)lvl); }
JNIEXPORT jint     JNICALL Java_com_eaquel_service_GameAnticheat_nativeGetThreatLevel(JNIEnv*, jobject)     { return (jint)g_threat_level.load(); }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeDetectSuspiciousProcs(JNIEnv*, jobject)  { return detect_suspicious_processes()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeDetectSuspiciousLibs(JNIEnv*, jobject)   { return detect_suspicious_libs()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeDetectMitm(JNIEnv*, jobject)         { return detect_mitm_proxy()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeCheckVerifiedBoot(JNIEnv*, jobject)  { return check_verified_boot()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeCheckEncryption(JNIEnv*, jobject)    { return check_encryption()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeCheckTee(JNIEnv*, jobject)           { return check_tee()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeCheckFridaGadget(JNIEnv*, jobject)   { return detect_frida_gadget()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jint     JNICALL Java_com_eaquel_service_GameAnticheat_nativeCountLibraries(JNIEnv*, jobject)     { return static_cast<jint>(count_mapped_libs()); }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeCheckArtIntegrity(JNIEnv*, jobject)  { return check_art_integrity()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeCheckZygoteParent(JNIEnv*, jobject)  { return check_zygote_parent()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeDetectVpn(JNIEnv*, jobject)          { return detect_vpn()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeDetectAdbAlwaysOn(JNIEnv*, jobject)  { return detect_adb_always_on()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeCheckTimingAttack(JNIEnv*, jobject)  { return detect_timing_attack()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeCheckVmAcceleration(JNIEnv*, jobject){ return detect_vm_accel_miss()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_com_eaquel_service_GameAnticheat_nativeProtectSelf(JNIEnv*, jobject)        { return protect_self()?JNI_TRUE:JNI_FALSE; }

JNIEXPORT jstring JNICALL Java_com_eaquel_service_GameAnticheat_nativeFullScan(JNIEnv* e, jobject) {
    bool r=detect_root(),em=detect_emulator(),d=detect_debugger(),h=detect_hook(),t=detect_tamper();
    int threats=(r?1:0)+(em?1:0)+(d?1:0)+(h?1:0)+(t?1:0); g_threat_level.store(threats);
    std::ostringstream o;
    o<<"{"<<"\"root\":"<<(r?"true":"false")<<","<<"\"emulator\":"<<(em?"true":"false")<<","
     <<"\"debugger\":"<<(d?"true":"false")<<","<<"\"hook\":"<<(h?"true":"false")<<","
     <<"\"tamper\":"<<(t?"true":"false")<<","<<"\"threats\":"<<threats<<"}";
    return s2j(e,o.str());
}

JNIEXPORT jstring JNICALL Java_com_eaquel_service_GameAnticheat_nativeComprehensiveScan(JNIEnv* e, jobject, jstring jp) {
    return s2j(e, full_scan_json(j2s(e,jp).c_str()));
}

} 
