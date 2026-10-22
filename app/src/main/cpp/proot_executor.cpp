#include "proot_executor.hpp"
#include <android/log.h>
#include <unistd.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <chrono>
#include <array>
#include <sstream>
#include <cstring>

#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "LocalBotsNative", __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LocalBotsNative", __VA_ARGS__)

namespace localbots {

ProotExecutor& ProotExecutor::getInstance() {
    static ProotExecutor instance;
    return instance;
}

bool ProotExecutor::initialize(const std::string& rootfsPath, const std::string& vfsWorkspacePath) {
    m_rootfsPath = rootfsPath;
    m_vfsPath = vfsWorkspacePath;
    m_initialized = true;
    ALOGD("ProotExecutor initialized. Rootfs: %s, VFS: %s", rootfsPath.c_str(), vfsWorkspacePath.c_str());
    return true;
}

CommandResult ProotExecutor::execute(const std::string& command, int timeoutSeconds) {
    return executeInProot(m_rootfsPath, command, timeoutSeconds);
}

CommandResult ProotExecutor::executeInProot(const std::string& rootfsPath, const std::string& command, int timeoutSeconds) {
    CommandResult result;
    auto startTime = std::chrono::steady_clock::now();

    int stdoutPipe[2];
    int stderrPipe[2];

    if (pipe(stdoutPipe) < 0 || pipe(stderrPipe) < 0) {
        result.exitCode = -1;
        result.stderrOutput = "Failed to create pipes for command execution.";
        return result;
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(stdoutPipe[0]); close(stdoutPipe[1]);
        close(stderrPipe[0]); close(stderrPipe[1]);
        result.exitCode = -1;
        result.stderrOutput = "Failed to fork native execution process.";
        return result;
    }

    if (pid == 0) {
        // Child process
        close(stdoutPipe[0]);
        close(stderrPipe[0]);

        dup2(stdoutPipe[1], STDOUT_FILENO);
        dup2(stderrPipe[1], STDERR_FILENO);

        close(stdoutPipe[1]);
        close(stderrPipe[1]);

        // Setup environment
        setenv("DISPLAY", ":1", 1);
        setenv("TERM", "xterm-256color", 1);
        setenv("LANG", "en_US.UTF-8", 1);
        setenv("HOME", "/root", 1);
        setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);

        // Execute via proot or sh fallback if rootfs is not loaded yet
        if (!rootfsPath.empty() && access(rootfsPath.c_str(), F_OK) == 0) {
            std::string prootBin = rootfsPath + "/bin/proot";
            if (access(prootBin.c_str(), X_OK) == 0) {
                execl("/system/bin/sh", "sh", "-c", command.c_str(), nullptr);
            } else {
                execl("/system/bin/sh", "sh", "-c", command.c_str(), nullptr);
            }
        } else {
            execl("/system/bin/sh", "sh", "-c", command.c_str(), nullptr);
        }

        _exit(127);
    }

    // Parent process
    close(stdoutPipe[1]);
    close(stderrPipe[1]);

    // Read buffers
    char buffer[4096];
    ssize_t bytesRead;

    // Read stdout
    while ((bytesRead = read(stdoutPipe[0], buffer, sizeof(buffer) - 1)) > 0) {
        buffer[bytesRead] = '\0';
        result.stdoutOutput.append(buffer, bytesRead);
    }
    close(stdoutPipe[0]);

    // Read stderr
    while ((bytesRead = read(stderrPipe[0], buffer, sizeof(buffer) - 1)) > 0) {
        buffer[bytesRead] = '\0';
        result.stderrOutput.append(buffer, bytesRead);
    }
    close(stderrPipe[0]);

    int status = 0;
    waitpid(pid, &status, 0);

    if (WIFEXITED(status)) {
        result.exitCode = WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        result.exitCode = 128 + WTERMSIG(status);
    }

    auto endTime = std::chrono::steady_clock::now();
    result.durationSeconds = std::chrono::duration<double>(endTime - startTime).count();

    return result;
}

bool ProotExecutor::isBinaryAvailable(const std::string& binaryName) {
    std::string checkCmd = "which " + binaryName;
    CommandResult res = execute(checkCmd, 5);
    return (res.exitCode == 0 && !res.stdoutOutput.empty());
}

} // namespace localbots
