#ifndef PROOT_EXECUTOR_HPP
#define PROOT_EXECUTOR_HPP

#include <string>
#include <vector>
#include <functional>

namespace localbots {

struct CommandResult {
    int exitCode = -1;
    std::string stdoutOutput;
    std::string stderrOutput;
    double durationSeconds = 0.0;
};

class ProotExecutor {
public:
    static ProotExecutor& getInstance();

    bool initialize(const std::string& rootfsPath, const std::string& vfsWorkspacePath);
    CommandResult execute(const std::string& command, int timeoutSeconds = 60);
    CommandResult executeInProot(const std::string& rootfsPath, const std::string& command, int timeoutSeconds = 60);
    bool isBinaryAvailable(const std::string& binaryName);

private:
    ProotExecutor() = default;
    std::string m_rootfsPath;
    std::string m_vfsPath;
    bool m_initialized = false;
};

} // namespace localbots

#endif // PROOT_EXECUTOR_HPP
