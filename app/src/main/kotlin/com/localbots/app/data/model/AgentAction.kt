package com.localbots.app.data.model

import kotlinx.serialization.Serializable

enum class ActionCategory {
    DESKTOP_GUI,
    FIREFOX_BROWSER,
    VIRTUAL_FILE_SYSTEM,
    TOOLCHAIN_RUNTIME
}

enum class ActionStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    ERROR
}

object ActionTypes {
    // Category 1: Desktop GUI & Input Actions (10 Actions)
    const val DESKTOP_SCREENSHOT = "Desktop_Screenshot"
    const val UPDATE_MOUSE_POSITION = "Update_Mouse_Position"
    const val RIGHT_CLICK = "right-click"
    const val LEFT_CLICK = "left-click"
    const val DOUBLE_CLICK = "double-click"
    const val MIDDLE_CLICK = "middle-click"
    const val MOUSE_DRAG = "mouse-drag"
    const val MOUSE_SCROLL = "mouse-scroll"
    const val KEYBOARD_TYPE = "keyboard-type"
    const val KEYBOARD_SHORTCUT = "keyboard-shortcut"

    // Category 2: Firefox Browser Automation Actions (8 Actions)
    const val BROWSER_LAUNCH = "Browser_Launch"
    const val BROWSER_NAVIGATE = "Browser_Navigate"
    const val BROWSER_SEARCH = "Browser_Search"
    const val BROWSER_CLICK_ELEMENT = "Browser_Click_Element"
    const val BROWSER_EXTRACT_DOM = "Browser_Extract_DOM"
    const val BROWSER_EXECUTE_JS = "Browser_Execute_JS"
    const val BROWSER_MANAGE_TABS = "Browser_Manage_Tabs"
    const val BROWSER_DOWNLOAD_FILE = "Browser_Download_File"

    // Category 3: VFS Actions (8 Actions)
    const val VFS_PUSH_TEXT = "VFS_Push_Text"
    const val VFS_APPEND_TEXT = "VFS_Append_Text"
    const val VFS_READ_FILE = "VFS_Read_File"
    const val VFS_CREATE_FOLDER = "VFS_Create_Folder"
    const val VFS_DELETE_ITEM = "VFS_Delete_Item"
    const val VFS_RENAME_MOVE = "VFS_Rename_Move"
    const val VFS_LIST_TREE = "VFS_List_Tree"
    const val VFS_SEARCH_FILES = "VFS_Search_Files"

    // Category 4: Pre-installed Toolchain & Runtime Actions (6 Actions)
    const val BASH_EXEC = "Bash_Exec"
    const val PYTHON_RUN = "Python_Run"
    const val TYPESCRIPT_COMPILE_RUN = "TypeScript_Compile_Run"
    const val GIT_OPERATION = "Git_Operation"
    const val PROCESS_MONITOR = "Process_Monitor"
    const val CLIPBOARD_SYNC = "Clipboard_Sync"

    fun getCategory(actionType: String): ActionCategory {
        return when (actionType) {
            DESKTOP_SCREENSHOT, UPDATE_MOUSE_POSITION, RIGHT_CLICK, LEFT_CLICK,
            DOUBLE_CLICK, MIDDLE_CLICK, MOUSE_DRAG, MOUSE_SCROLL,
            KEYBOARD_TYPE, KEYBOARD_SHORTCUT -> ActionCategory.DESKTOP_GUI

            BROWSER_LAUNCH, BROWSER_NAVIGATE, BROWSER_SEARCH, BROWSER_CLICK_ELEMENT,
            BROWSER_EXTRACT_DOM, BROWSER_EXECUTE_JS, BROWSER_MANAGE_TABS,
            BROWSER_DOWNLOAD_FILE -> ActionCategory.FIREFOX_BROWSER

            VFS_PUSH_TEXT, VFS_APPEND_TEXT, VFS_READ_FILE, VFS_CREATE_FOLDER,
            VFS_DELETE_ITEM, VFS_RENAME_MOVE, VFS_LIST_TREE,
            VFS_SEARCH_FILES -> ActionCategory.VIRTUAL_FILE_SYSTEM

            BASH_EXEC, PYTHON_RUN, TYPESCRIPT_COMPILE_RUN, GIT_OPERATION,
            PROCESS_MONITOR, CLIPBOARD_SYNC -> ActionCategory.TOOLCHAIN_RUNTIME

            else -> ActionCategory.TOOLCHAIN_RUNTIME
        }
    }
}

@Serializable
data class AgentActionExecution(
    val id: String,
    val actionName: String,
    val category: ActionCategory,
    val rawJson: String,
    val parametersSummary: String = "",
    val status: ActionStatus = ActionStatus.PENDING,
    val output: String? = null,
    val error: String? = null,
    val durationMs: Long = 0L,
    val imageBase64: String? = null
)
