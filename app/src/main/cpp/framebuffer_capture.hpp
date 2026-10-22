#ifndef FRAMEBUFFER_CAPTURE_HPP
#define FRAMEBUFFER_CAPTURE_HPP

#include <vector>
#include <string>
#include <cstdint>

namespace localbots {

struct ScreenCaptureData {
    int width = 0;
    int height = 0;
    int channels = 4; // RGBA
    std::vector<uint8_t> pixels;
    bool success = false;
    std::string errorMessage;
};

class FramebufferCapture {
public:
    static FramebufferCapture& getInstance();

    ScreenCaptureData captureDisplay(int displayNumber, const std::string& region, const std::string& format);

private:
    FramebufferCapture() = default;
};

} // namespace localbots

#endif // FRAMEBUFFER_CAPTURE_HPP
