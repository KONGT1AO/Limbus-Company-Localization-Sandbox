#include <cstring>
#include <cstdio>
#include <limits.h>
#include <unistd.h>
#include <stdlib.h>
#include <syscall.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <ctype.h>
#include "MapsRedirector.h"
#include "Log.h"
#include "SandboxFs.h"

static int create_temp_file() {
    char pattern[PATH_MAX] = {0};
    char *cache_dir = getenv("V_NATIVE_PATH");
    if (cache_dir == NULL || cache_dir[0] == '\0') {
        cache_dir = const_cast<char *>("/data/local/tmp");
    }
    int fd = static_cast<int>(syscall(__NR_openat, AT_FDCWD, cache_dir,
                                      O_RDWR | O_CLOEXEC | O_TMPFILE | O_EXCL, 0600));
    if (fd != -1) {
        return fd;
    }

    snprintf(pattern, sizeof(pattern), "%s/dev_maps_%d_%d", cache_dir, getpid(), gettid());
    fd = static_cast<int>(syscall(__NR_openat, AT_FDCWD, pattern,
                                  O_CREAT | O_RDWR | O_TRUNC | O_CLOEXEC, 0600));
    if (fd == -1) {
        ALOGE("fake_maps: cannot create tmp file, errno = %d", errno);
        return -1;
    }
    unlink(pattern);
    return fd;
}

static char *find_maps_pathname(char *line) {
    if (line == NULL) {
        return NULL;
    }
    unsigned long start = 0;
    unsigned long end = 0;
    unsigned long offset = 0;
    unsigned long inode = 0;
    char permissions[8] = {0};
    char device[32] = {0};
    int consumed = 0;
    int parsed = sscanf(line, "%lx-%lx %7s %lx %31s %lu%n",
                        &start, &end, permissions, &offset, device, &inode, &consumed);
    if (parsed != 6 || consumed <= 0) {
        return NULL;
    }
    char *p = line + consumed;
    while (*p == ' ' || *p == '\t') {
        ++p;
    }
    if (*p == '\0') {
        return NULL;
    }
    return p;
}

static bool match_host_pkg(const char *path) {
    return strstr(path, "io.busniess.va") != NULL
           || strstr(path, "io.limbuspe.box") != NULL
           || strstr(path, "com.example.limbuszhcn") != NULL
           || strstr(path, "/virtual/") != NULL
           || strstr(path, "libv++") != NULL;
}

static void sanitize_limbus_exec_permissions_inplace(char *line) {
    if (line == NULL) {
        return;
    }
    char *space = strchr(line, ' ');
    if (space == NULL) {
        return;
    }
    while (*space == ' ') {
        ++space;
    }
    // 将任何 rwxp / -wxp 映射行伪装为 r-xp，防止 AppSealing 检出可写可执行页
    if (space[0] != '\0' && space[1] == 'w' && space[2] == 'x') {
        space[1] = '-';
    }
}

static bool is_limbus_anonymous_exec_trampoline(const char *line) {
    const char *real_lib_dir = getenv("V_LIMBUS_REAL_LIB_DIR");
    if (real_lib_dir == NULL || real_lib_dir[0] == '\0' || line == NULL) {
        return false;
    }

    unsigned long start = 0;
    unsigned long end = 0;
    unsigned long offset = 0;
    unsigned long inode = 0;
    char permissions[5] = {0};
    char device[16] = {0};
    int consumed = 0;
    int parsed = sscanf(line, "%lx-%lx %4s %lx %15s %lu %n",
                        &start, &end, permissions, &offset, device, &inode, &consumed);
    if (parsed != 6 || start >= end || permissions[2] != 'x'
            || offset != 0 || inode != 0) {
        return false;
    }
    if (strcmp(permissions, "r-xp") != 0 || end - start != 4096) {
        return false;
    }
    while (line[consumed] == ' ' || line[consumed] == '\t') {
        ++consumed;
    }
    return line[consumed] == '\0';
}

static const char *rewrite_limbus_map_path(const char *path, char *buffer, size_t size) {
    if (path == NULL || buffer == NULL || size == 0) {
        return path;
    }

    const char *real_lib_dir = getenv("V_LIMBUS_REAL_LIB_DIR");
    bool valid_real_lib_dir = real_lib_dir != NULL
            && real_lib_dir[0] != '\0'
            && strstr(real_lib_dir, "/virtual/") == NULL
            && strstr(real_lib_dir, "io.limbuspe.box") == NULL
            && strstr(real_lib_dir, "com.example.limbuszhcn") == NULL;

    // 兼容 Android 16 / vivo 内核将宿主沙箱挂载路径输出为 /data/data/<host>/virtual/... 或 /data/user/0/<host>/virtual/...
    const char *app_lib_marker = "/virtual/data/app/com.ProjectMoon.LimbusCompany/lib/";
    const char *app_lib_pos = strstr(path, app_lib_marker);
    if (app_lib_pos != NULL && valid_real_lib_dir) {
        const char *relative_lib = app_lib_pos + strlen(app_lib_marker);
        size_t dir_len = strlen(real_lib_dir);
        bool has_slash = dir_len > 0 && real_lib_dir[dir_len - 1] == '/';
        int written = snprintf(buffer, size, has_slash ? "%s%s" : "%s/%s", real_lib_dir, relative_lib);
        if (written > 0 && static_cast<size_t>(written) < size) {
            return buffer;
        }
    }

    struct VirtualMarkerRule {
        const char *marker;
        const char *visible_prefix;
    };
    static const VirtualMarkerRule marker_rules[] = {
            {
                    "/virtual/data/user/0/com.ProjectMoon.LimbusCompany/",
                    "/data/user/0/com.ProjectMoon.LimbusCompany/"
            },
            {
                    "/virtual/data/user_de/0/com.ProjectMoon.LimbusCompany/",
                    "/data/user_de/0/com.ProjectMoon.LimbusCompany/"
            },
            {
                    "/virtual/data/data/com.ProjectMoon.LimbusCompany/",
                    "/data/data/com.ProjectMoon.LimbusCompany/"
            },
            {
                    "/virtual/storage/emulated/0/",
                    "/storage/emulated/0/"
            },
    };
    if (strstr(path, "io.limbuspe.box") != NULL
            || strstr(path, "com.example.limbuszhcn") != NULL
            || strstr(path, "/virtual/") != NULL) {
        for (size_t i = 0; i < sizeof(marker_rules) / sizeof(marker_rules[0]); ++i) {
            const char *pos = strstr(path, marker_rules[i].marker);
            if (pos == NULL) {
                continue;
            }
            const char *suffix = pos + strlen(marker_rules[i].marker);
            int written = snprintf(buffer, size, "%s%s", marker_rules[i].visible_prefix, suffix);
            if (written > 0 && static_cast<size_t>(written) < size) {
                return buffer;
            }
        }
    }

    // 兼容 [anon:dalvik-...] 等命名匿名段中内嵌虚拟沙箱路径的情况
    if (path[0] == '[' && (strstr(path, "io.limbuspe.box") != NULL
            || strstr(path, "com.example.limbuszhcn") != NULL
            || strstr(path, "/virtual/") != NULL)) {
        struct HostVirtualReplace {
            const char *from;
            const char *to;
        };
        static const HostVirtualReplace anon_rules[] = {
                {"/data/data/io.limbuspe.box/virtual/data/user/0/com.ProjectMoon.LimbusCompany/", "/data/user/0/com.ProjectMoon.LimbusCompany/"},
                {"/data/user/0/io.limbuspe.box/virtual/data/user/0/com.ProjectMoon.LimbusCompany/", "/data/user/0/com.ProjectMoon.LimbusCompany/"},
                {"/data/data/io.limbuspe.box/virtual/data/user_de/0/com.ProjectMoon.LimbusCompany/", "/data/user_de/0/com.ProjectMoon.LimbusCompany/"},
                {"/data/user/0/io.limbuspe.box/virtual/data/user_de/0/com.ProjectMoon.LimbusCompany/", "/data/user_de/0/com.ProjectMoon.LimbusCompany/"},
                {"/data/data/io.limbuspe.box/virtual/storage/emulated/0/", "/storage/emulated/0/"},
                {"/data/user/0/io.limbuspe.box/virtual/storage/emulated/0/", "/storage/emulated/0/"},
                {"/data/data/com.example.limbuszhcn/virtual/data/user/0/com.ProjectMoon.LimbusCompany/", "/data/user/0/com.ProjectMoon.LimbusCompany/"},
                {"/data/user/0/com.example.limbuszhcn/virtual/data/user/0/com.ProjectMoon.LimbusCompany/", "/data/user/0/com.ProjectMoon.LimbusCompany/"},
        };
        for (size_t i = 0; i < sizeof(anon_rules) / sizeof(anon_rules[0]); ++i) {
            const char *pos = strstr(path, anon_rules[i].from);
            if (pos == NULL) {
                continue;
            }
            size_t prefix_len = static_cast<size_t>(pos - path);
            const char *rest = pos + strlen(anon_rules[i].from);
            int written = snprintf(buffer, size, "%.*s%s%s",
                                   static_cast<int>(prefix_len), path, anon_rules[i].to, rest);
            if (written > 0 && static_cast<size_t>(written) < size) {
                return buffer;
            }
        }
        return path;
    }

    if (strstr(path, "com.ProjectMoon.LimbusCompany") == NULL) {
        return path;
    }
    const char *lib_name = strrchr(path, '/');
    if (lib_name == NULL || strstr(lib_name, ".so") == NULL || !valid_real_lib_dir) {
        return path;
    }
    int written = snprintf(buffer, size, "%s%s", real_lib_dir, lib_name);
    if (written <= 0 || static_cast<size_t>(written) >= size) {
        return path;
    }
    return buffer;
}

static bool is_proc_path(const char *pathname, const char **relative) {
    if (pathname == NULL || strncmp(pathname, "/proc/", sizeof("/proc/") - 1) != 0) {
        return false;
    }
    *relative = pathname + sizeof("/proc/") - 1;
    return true;
}

static bool is_self_or_pid_path(const char *relative) {
    if (strncmp(relative, "self/", sizeof("self/") - 1) == 0) {
        return true;
    }
    const char *p = relative;
    while (*p != '\0' && *p != '/') {
        if (!isdigit(*p)) {
            return false;
        }
        ++p;
    }
    return *p == '/';
}

static bool ends_with(const char *text, const char *suffix) {
    size_t text_len = strlen(text);
    size_t suffix_len = strlen(suffix);
    return text_len >= suffix_len && strcmp(text + text_len - suffix_len, suffix) == 0;
}

static bool is_proc_maps_file(const char *relative) {
    return is_self_or_pid_path(relative)
           && (ends_with(relative, "/maps") || ends_with(relative, "/smaps"));
}

static bool is_proc_cmdline_file(const char *relative) {
    return is_self_or_pid_path(relative) && ends_with(relative, "/cmdline");
}

static bool is_proc_comm_file(const char *relative) {
    return is_self_or_pid_path(relative)
           && ends_with(relative, "/comm");
}

static bool is_proc_status_file(const char *relative) {
    return is_self_or_pid_path(relative) && ends_with(relative, "/status");
}

static bool is_proc_modules_file(const char *relative) {
    return strcmp(relative, "modules") == 0;
}

static bool should_log_fake_proc(const char *pathname) {
    static int cmdline_logs = 0;
    static int comm_logs = 0;
    static int status_logs = 0;
    static int other_logs = 0;
    const char *relative = NULL;
    if (!is_proc_path(pathname, &relative)) {
        return other_logs++ < 8;
    }
    if (is_proc_cmdline_file(relative)) {
        return cmdline_logs++ < 4;
    }
    if (is_proc_comm_file(relative)) {
        return comm_logs++ < 4;
    }
    if (is_proc_status_file(relative)) {
        return status_logs++ < 4;
    }
    return other_logs++ < 8;
}

static void redirect_proc_maps_internal(const int fd, const int fake_fd) {
    char line[PATH_MAX];
    char *p = line, *e;
    size_t n = PATH_MAX - 1;
    ssize_t r;
    while ((r = TEMP_FAILURE_RETRY(read(fd, p, n))) > 0) {
        p[r] = '\0';
        p = line; // search begin at line start

        while ((e = strchr(p, '\n')) != NULL) {
            e[0] = '\0';

            if (is_limbus_anonymous_exec_trampoline(p)) {
                static int trampoline_logs = 0;
                if (trampoline_logs++ < 8) {
                    ALOGE("fake_maps: remove anonymous executable trampoline: %s", p);
                }
                p = e + 1;
                continue;
            }

            sanitize_limbus_exec_permissions_inplace(p);

            char *path = find_maps_pathname(p);
            if (path != NULL && (path[0] == '/' || path[0] == '[')) {
                char temp[PATH_MAX];
                const char *real_path = path[0] == '/'
                        ? reverse_relocate_path(path, temp, sizeof(temp))
                        : path;
                char visible_temp[PATH_MAX];
                const char *visible_path = rewrite_limbus_map_path(real_path, visible_temp, sizeof(visible_temp));
                if (visible_path != NULL && match_host_pkg(visible_path)) {
                    static int removed_logs = 0;
                    if (removed_logs++ < 24) {
                        ALOGE("remove map item: %s", p);
                    }
                    visible_path = NULL;
                }

                write(fake_fd, p, path - p);
                if (visible_path != NULL) {
                    write(fake_fd, visible_path, strlen(visible_path));
                }
                write(fake_fd, "\n", 1);
            } else {
                e[0] = '\n';
                write(fake_fd, p, e - p + 1);
            }

            p = e + 1;
        }
        if (p == line) { // !any_entry
            ALOGE("fake_maps: cannot process line larger than %u bytes!", PATH_MAX);
            goto __break;
        } //if

        const size_t remain = strlen(p);
        if (remain <= (PATH_MAX / 2)) {
            memcpy(line, p, remain * sizeof(p[0]));
        } else {
            memmove(line, p, remain * sizeof(p[0]));
        } //if

        p = line + remain;
        n = PATH_MAX - 1 - remain;
    }

    __break:
    return;
}

int redirect_proc_maps(const char *const pathname, const int flags, const int mode) {
    const char *relative = NULL;
    if (!is_proc_path(pathname, &relative) || !is_proc_maps_file(relative)) {
        return 0;
    }
    ALOGE("start redirect: %s", pathname);

    int fd = syscall(__NR_openat, AT_FDCWD, pathname, flags, mode);
    if (fd == -1) {
        errno = EACCES;
        return -1;
    }

    int fake_fd = create_temp_file();
    if (fake_fd == -1) {
        ALOGE("fake_maps: create_temp_file failed, errno = %d", errno);
        errno = EACCES;
        return -1;
    }

    redirect_proc_maps_internal(fd, fake_fd);
    lseek(fake_fd, 0, SEEK_SET);
    syscall(__NR_close, fd);

    ALOGI("fake_maps: faked %s -> fd %d", pathname, fake_fd);
    return fake_fd;
}

static int fake_proc_small_file(const char *pathname, const char *content, size_t content_len) {
    int fake_fd = create_temp_file();
    if (fake_fd == -1) {
        ALOGE("fake_proc: create_temp_file failed for %s, errno = %d", pathname, errno);
        errno = EACCES;
        return -1;
    }
    write(fake_fd, content, content_len);
    lseek(fake_fd, 0, SEEK_SET);
    if (should_log_fake_proc(pathname)) {
        ALOGI("fake_proc: faked %s -> fd %d", pathname, fake_fd);
    }
    return fake_fd;
}

static int redirect_proc_status(const char *pathname, const int flags, const int mode) {
    int fd = syscall(__NR_openat, AT_FDCWD, pathname, flags, mode);
    if (fd == -1) {
        errno = EACCES;
        return -1;
    }
    int fake_fd = create_temp_file();
    if (fake_fd == -1) {
        syscall(__NR_close, fd);
        errno = EACCES;
        return -1;
    }

    char buffer[4096];
    ssize_t r;
    bool at_line_start = true;
    while ((r = TEMP_FAILURE_RETRY(read(fd, buffer, sizeof(buffer)))) > 0) {
        char *start = buffer;
        char *end = buffer + r;
        while (start < end) {
            char *line_end = static_cast<char *>(memchr(start, '\n', end - start));
            size_t line_len = line_end == NULL ? static_cast<size_t>(end - start)
                                               : static_cast<size_t>(line_end - start + 1);
            if (at_line_start && line_len >= 5 && strncmp(start, "Name:", 5) == 0) {
                write(fake_fd, "Name:\tLimbusCompany\n", sizeof("Name:\tLimbusCompany\n") - 1);
            } else {
                write(fake_fd, start, line_len);
            }
            at_line_start = line_end != NULL;
            start += line_len;
        }
    }
    syscall(__NR_close, fd);
    lseek(fake_fd, 0, SEEK_SET);
    if (should_log_fake_proc(pathname)) {
        ALOGI("fake_proc: faked %s -> fd %d", pathname, fake_fd);
    }
    return fake_fd;
}

int redirect_proc_file(const char *const pathname, const int flags, const int mode) {
    const char *relative = NULL;
    if (!is_proc_path(pathname, &relative)) {
        return 0;
    }
    if (is_proc_maps_file(relative)) {
        return redirect_proc_maps(pathname, flags, mode);
    }
    if (is_proc_cmdline_file(relative)) {
        static const char cmdline[] = "com.ProjectMoon.LimbusCompany";
        return fake_proc_small_file(pathname, cmdline, sizeof(cmdline));
    }
    if (is_proc_comm_file(relative)) {
        static const char comm[] = "LimbusCompany\n";
        return fake_proc_small_file(pathname, comm, sizeof(comm) - 1);
    }
    if (is_proc_status_file(relative)) {
        return redirect_proc_status(pathname, flags, mode);
    }
    if (is_proc_modules_file(relative)) {
        static const char empty_modules[] = "";
        return fake_proc_small_file(pathname, empty_modules, 0);
    }
    return 0;
}
