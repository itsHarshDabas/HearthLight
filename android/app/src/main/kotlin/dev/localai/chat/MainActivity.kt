package dev.localai.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localai.chat.ui.chat.ChatScreen
import dev.localai.chat.ui.chat.ChatViewModel
import dev.localai.chat.ui.images.ImagesScreen
import dev.localai.chat.ui.images.ImagesViewModel
import dev.localai.chat.ui.settings.SettingsScreen
import dev.localai.chat.ui.theme.LocalAiTheme
import dev.localai.core.ConversationSummary
import kotlinx.coroutines.launch

private enum class Screen { Chat, Images, Settings }

class MainActivity : ComponentActivity() {
    private val chatVm: ChatViewModel by viewModels()
    private val imagesVm: ImagesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as LocalAiApp).container
        setContent {
            LocalAiTheme {
                val firstRun = container.settings.state.value.serverUrl.isBlank()
                var screen by rememberSaveable { mutableStateOf(if (firstRun) Screen.Settings else Screen.Chat) }
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val conversations by chatVm.conversations.collectAsStateWithLifecycle()
                val chatState by chatVm.state.collectAsStateWithLifecycle()
                var confirmDelete by remember { mutableStateOf<ConversationSummary?>(null) }

                fun go(s: Screen) {
                    screen = s
                    scope.launch { drawer.close() }
                }
                val openDrawer: () -> Unit = { scope.launch { drawer.open() } }

                BackHandler(enabled = screen != Screen.Chat && !drawer.isOpen) { screen = Screen.Chat }

                ModalNavigationDrawer(
                    drawerState = drawer,
                    drawerContent = {
                        ModalDrawerSheet {
                            Spacer(Modifier.height(12.dp))
                            Text("Local AI", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
                            NavigationDrawerItem(
                                label = { Text("New chat") },
                                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                                selected = false,
                                onClick = { chatVm.newChat(); go(Screen.Chat) },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            NavigationDrawerItem(
                                label = { Text("Images") },
                                icon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                                selected = screen == Screen.Images,
                                onClick = { go(Screen.Images) },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            NavigationDrawerItem(
                                label = { Text("Settings") },
                                icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                                selected = screen == Screen.Settings,
                                onClick = { go(Screen.Settings) },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 28.dp))
                            Text("Recent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
                            LazyColumn {
                                items(conversations, key = { it.id }) { c ->
                                    NavigationDrawerItem(
                                        label = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        icon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null) },
                                        selected = screen == Screen.Chat && chatState.conversation.id == c.id,
                                        onClick = { chatVm.open(c.id); go(Screen.Chat) },
                                        badge = {
                                            Icon(
                                                Icons.Outlined.Delete,
                                                contentDescription = "Delete chat",
                                                modifier = Modifier.clickable { confirmDelete = c },
                                            )
                                        },
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                    )
                                }
                            }
                        }
                    },
                ) {
                    when (screen) {
                        Screen.Chat -> ChatScreen(chatVm, onOpenDrawer = openDrawer)
                        Screen.Images -> ImagesScreen(imagesVm, onOpenDrawer = openDrawer)
                        Screen.Settings -> SettingsScreen(container, onOpenDrawer = openDrawer, onSaved = {
                            chatVm.refreshModels()
                            imagesVm.refresh()
                            screen = Screen.Chat
                        })
                    }
                }

                confirmDelete?.let { c ->
                    AlertDialog(
                        onDismissRequest = { confirmDelete = null },
                        title = { Text("Delete chat?") },
                        text = { Text(c.title) },
                        confirmButton = { TextButton(onClick = { chatVm.delete(c.id); confirmDelete = null }) { Text("Delete") } },
                        dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
                    )
                }
            }
        }
    }
}
