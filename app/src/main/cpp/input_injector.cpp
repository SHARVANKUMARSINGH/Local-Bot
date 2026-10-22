#include "input_injector.hpp"
#include <android/log.h>
#include <sstream>

#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "LocalBotsNative", __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LocalBotsNative", __VA_ARGS__)

namespace localbots {

InputInjector& InputInjector::getInstance() {
    static InputInjector instance;
    return instance;
}

bool InputInjector::injectMouseMove(int x, int y, int z) {
    m_curX = x;
    m_curY = y;
    m_curZ = z;
    ALOGD("Injected MouseMove: [%d, %d, %d]", x, y, z);
    return true;
}

bool InputInjector::injectMouseClick(int button, int x, int y) {
    if (x >= 0 && y >= 0) {
        m_curX = x;
        m_curY = y;
    }
    ALOGD("Injected MouseClick: button=%d at [%d, %d]", button, m_curX, m_curY);
    return true;
}

bool InputInjector::injectMouseDrag(int startX, int startY, int endX, int endY) {
    ALOGD("Injected MouseDrag: from [%d, %d] to [%d, %d]", startX, startY, endX, endY);
    m_curX = endX;
    m_curY = endY;
    return true;
}

bool InputInjector::injectMouseScroll(const std::string& direction, int delta) {
    ALOGD("Injected MouseScroll: direction=%s, delta=%d", direction.c_str(), delta);
    return true;
}

bool InputInjector::injectKeyboardType(const std::string& text) {
    ALOGD("Injected KeyboardType: length=%zu chars", text.length());
    return true;
}

bool InputInjector::injectKeyboardShortcut(const std::vector<std::string>& keys) {
    std::string combo;
    for (size_t i = 0; i < keys.size(); ++i) {
        if (i > 0) combo += "+";
        combo += keys[i];
    }
    ALOGD("Injected KeyboardShortcut: %s", combo.c_str());
    return true;
}

} // namespace localbots
