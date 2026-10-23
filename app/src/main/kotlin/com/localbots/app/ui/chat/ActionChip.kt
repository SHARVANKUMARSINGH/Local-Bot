package com.localbots.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localbots.app.data.model.ActionCategory
import com.localbots.app.data.model.ActionStatus
import com.localbots.app.data.model.ActionTypes
import com.localbots.app.data.model.AgentActionExecution
import com.localbots.app.theme.TerminalBackground
import com.localbots.app.theme.TerminalCyan
import com.localbots.app.theme.TerminalGreen
import com.localbots.app.theme.TerminalRed

fun getIconForAction(actionName: String): ImageVector {
    return when (actionName) {
        ActionTypes.DESKTOP_SCREENSHOT -> Icons.Rounded.CameraAlt
        ActionTypes.UPDATE_MOUSE_POSITION, ActionTypes.LEFT_CLICK, ActionTypes.RIGHT_CLICK,
        ActionTypes.DOUBLE_CLICK, ActionTypes.MIDDLE_CLICK, ActionTypes.MOUSE_DRAG,
        ActionTypes.MOUSE_SCROLL -> Icons.Rounded.Mouse
        ActionTypes.KEYBOARD_TYPE, ActionTypes.KEYBOARD_SHORTCUT -> Icons.Rounded.Keyboard

        ActionTypes.BROWSER_LAUNCH, ActionTypes.BROWSER_NAVIGATE, ActionTypes.BROWSER_SEARCH,
        ActionTypes.BROWSER_CLICK_ELEMENT, ActionTypes.BROWSER_EXTRACT_DOM,
        ActionTypes.BROWSER_EXECUTE_JS, ActionTypes.BROWSER_MANAGE_TABS -> Icons.Rounded.Public
        ActionTypes.BROWSER_DOWNLOAD_FILE -> Icons.Rounded.Download

        ActionTypes.VFS_PUSH_TEXT, ActionTypes.VFS_APPEND_TEXT, ActionTypes.VFS_READ_FILE -> Icons.Rounded.Description
        ActionTypes.VFS_CREATE_FOLDER, ActionTypes.VFS_DELETE_ITEM,
        ActionTypes.VFS_RENAME_MOVE, ActionTypes.VFS_LIST_TREE -> Icons.Rounded.FolderOpen
        ActionTypes.VFS_SEARCH_FILES -> Icons.Rounded.Search

        ActionTypes.BASH_EXEC -> Icons.Rounded.Terminal
        ActionTypes.PYTHON_RUN, ActionTypes.TYPESCRIPT_COMPILE_RUN -> Icons.Rounded.Code
        ActionTypes.GIT_OPERATION -> Icons.Rounded.Terminal
        ActionTypes.PROCESS_MONITOR -> Icons.Rounded.Memory
        ActionTypes.CLIPBOARD_SYNC -> Icons.Rounded.ContentCopy

        else -> Icons.Rounded.Terminal
    }
}

@Composable
fun ActionChip(
    action: AgentActionExecution,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }
    val icon = getIconForAction(action.actionName)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { isExpanded = !isExpanded },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Action Icon
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = action.actionName,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = action.actionName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (action.parametersSummary.isNotBlank()) {
                        Text(
                            text = action.parametersSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }

                // Status Indicator
                when (action.status) {
                    ActionStatus.RUNNING -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    ActionStatus.SUCCESS -> Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Success",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    ActionStatus.ERROR -> Icon(
                        imageVector = Icons.Rounded.Error,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    ActionStatus.PENDING -> Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.outline)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Icon(
                    imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = "Toggle Details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    // Duration badge
                    if (action.durationMs > 0) {
                        Text(
                            text = "Execution Time: ${action.durationMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Raw Payload / Output viewer
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = TerminalBackground
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "Action Payload:",
                                style = MaterialTheme.typography.labelSmall,
                                color = TerminalCyan,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = action.rawJson,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                fontFamily = FontFamily.Monospace,
                                color = TerminalGreen
                            )

                            if (!action.output.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Execution Result:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TerminalCyan,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = action.output,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    fontFamily = FontFamily.Monospace,
                                    color = TerminalGreen
                                )
                            }

                            if (!action.error.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Error Trace:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TerminalRed,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = action.error,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    fontFamily = FontFamily.Monospace,
                                    color = TerminalRed
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
