#ifndef INPUT_INJECTOR_HPP
#define INPUT_INJECTOR_HPP

#include <string>
#include <vector>

namespace localbots {

enum class MouseEventType {
    MOVE = 0,
    LEFT_CLICK = 1,
    RIGHT_CLICK = 2,
    DOUBLE_CLICK = 3,
    MIDDLE_CLICK = 4,
    DRAG = 5,
    SCROLL = 6
};

class InputInjector {
public:
    static InputInjector& getInstance();

    bool injectMouseMove(int x, int y, int z = 0);
    bool injectMouseClick(int button, int x = -1, int y = -1);
    bool injectMouseDrag(int startX, int startY, int endX, int endY);
    bool injectMouseScroll(const std::string& direction, int delta);
    bool injectKeyboardType(const std::string& text);
    bool injectKeyboardShortcut(const std::vector<std::string>& keys);

private:
    InputInjector() = default;
    int m_curX = 0;
    int m_curY = 0;
    int m_curZ = 0;
};

} // namespace localbots

#endif // INPUT_INJECTOR_HPP
