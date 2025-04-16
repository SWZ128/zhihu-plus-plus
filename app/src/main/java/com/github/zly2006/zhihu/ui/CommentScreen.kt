@file:Suppress("FunctionName")

package com.github.zly2006.zhihu.ui

import android.content.Context.MODE_PRIVATE
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.github.zly2006.zhihu.CommentHolder
import com.github.zly2006.zhihu.NavDestination
import com.github.zly2006.zhihu.data.AccountData
import com.github.zly2006.zhihu.data.DataHolder
import com.github.zly2006.zhihu.theme.Typography
import com.github.zly2006.zhihu.ui.components.WebviewComp
import com.github.zly2006.zhihu.ui.components.loadZhihu
import com.github.zly2006.zhihu.viewmodel.CommentItem
import com.github.zly2006.zhihu.viewmodel.comment.BaseCommentViewModel
import com.github.zly2006.zhihu.viewmodel.comment.ChildCommentViewModel
import com.github.zly2006.zhihu.viewmodel.comment.RootCommentViewModel
import io.ktor.client.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.*

private val HMS = SimpleDateFormat("HH:mm:ss")
private val MDHMS = SimpleDateFormat("MM-dd HH:mm:ss")
val YMDHMS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentScreen(
    httpClient: HttpClient,
    content: () -> NavDestination,
    activeCommentItem: CommentItem? = null,
    onChildCommentClick: (CommentItem) -> Unit
) {
    val context = LocalContext.current
    var commentInput by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    val preferences = remember {
        context.getSharedPreferences(PREFERENCE_NAME, MODE_PRIVATE)
    }
    val useWebview = remember { preferences.getBoolean("commentsUseWebview", true) }
    val pinWebview = remember { preferences.getBoolean("commentsPinWebview", false) }

    // 根据内容类型选择合适的ViewModel
    val viewModel: BaseCommentViewModel = when (val content = content()) {
        is CommentHolder -> remember {
            // 子评论不进行状态保存
            ChildCommentViewModel(content)
        }

        else -> viewModel {
            RootCommentViewModel(content)
        }
    }

    val listState = rememberLazyListState()

    // 监控滚动位置以实现加载更多
    val loadMore = remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItemsCount = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0

            lastVisibleItemIndex >= totalItemsCount - 3 && !viewModel.isLoading && viewModel?.isEnd == false
        }
    }

    // 监控滚动加载更多
    LaunchedEffect(loadMore.value) {
        if (loadMore.value && viewModel.errorMessage == null) {
            viewModel.loadMore(context)
        }
    }

    // 初始加载评论
    LaunchedEffect(content) {
        if (viewModel.article != content()) {
            error("Internal Error: Detected content mismatch")
        }
        if (viewModel.errorMessage == null) {
            viewModel.refresh(context)
        }
    }

    // 提交评论函数
    fun submitComment() {
        if (commentInput.isBlank() || isSending) return

        isSending = true
        viewModel.submitComment(content(), commentInput, httpClient, context) {
            commentInput = ""
            isSending = false
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 评论内容区域
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 100.dp)
                .fillMaxHeight()
                .align(Alignment.BottomCenter),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                CommentTopText(content())
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        viewModel.isLoading && viewModel.comments.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }

                        viewModel.errorMessage != null && viewModel.comments.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(viewModel.errorMessage!!, color = MaterialTheme.colorScheme.error)
                            }
                        }

                        viewModel.comments.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("暂无评论")
                            }
                        }

                        else -> {
                            @Composable
                            fun Comment(
                                commentItem: CommentItem,
                                onChildCommentClick: (CommentItem) -> Unit
                            ) {
                                var isLiked by remember { mutableStateOf(commentItem.item.liked) }
                                var likeCount by remember { mutableStateOf(commentItem.item.likeCount) }
                                var isLikeLoading by remember { mutableStateOf(false) }
                                val replyingTo = if (!commentItem.item.replyCommentId.isNullOrEmpty()) {
                                    viewModel.getCommentById(commentItem.item.replyCommentId)
                                } else null
                                CommentItem(
                                    comment = commentItem,
                                    replyingTo = replyingTo,
                                    httpClient = httpClient,
                                    useWebview = useWebview,
                                    pinWebview = pinWebview,
                                    isLiked = isLiked,
                                    likeCount = likeCount,
                                    isLikeLoading = isLikeLoading,
                                    toggleLike = {
                                        viewModel.toggleLikeComment(
                                            httpClient = httpClient,
                                            commentData = commentItem.item,
                                            context = context,
                                        ) {
                                            val newLikeState = !isLiked
                                            isLiked = newLikeState
                                            likeCount += if (newLikeState) 1 else -1
                                            commentItem.item.liked = newLikeState
                                            commentItem.item.likeCount = likeCount
                                        }
                                    },
                                    onChildCommentClick = onChildCommentClick,
                                )
                            }
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                if (activeCommentItem != null) {
                                    item(0) {
                                        Column {
                                            Comment(activeCommentItem) { }
                                            HorizontalDivider()
                                        }
                                    }
                                }

                                items(viewModel.comments) { commentItem ->
                                    Comment(commentItem) { comment ->
                                        if (comment.clickTarget != null) {
                                            onChildCommentClick(comment)
                                        }
                                    }
                                }

                                if (viewModel.isLoading && viewModel.comments.isNotEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 评论输入框
                Surface(
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = commentInput,
                            onValueChange = { commentInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("写下你的评论...") },
                            singleLine = false,
                            maxLines = 3,
                            colors = TextFieldDefaults.colors()
                        )

                        IconButton(
                            onClick = { submitComment() },
                            enabled = !isSending && commentInput.isNotBlank()
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Send,
                                contentDescription = "发送评论",
                                tint = if (!isSending && commentInput.isNotBlank())
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
@Preview(showBackground = true)
fun CommentTopText(content: NavDestination? = null) {
    Text(
        if (content is CommentHolder) "回复"
        else "评论",
        style = Typography.bodyMedium.copy(
            fontWeight = FontWeight.Bold,
        ),
        modifier = Modifier.fillMaxWidth().height(26.dp),
        textAlign = TextAlign.Center,
        fontSize = 18.sp
    )
}

@Composable
private fun CommentItem(
    comment: CommentItem,
    replyingTo: CommentItem? = null,
    httpClient: HttpClient,
    useWebview: Boolean,
    pinWebview: Boolean,
    isLiked: Boolean = false,
    likeCount: Int = 0,
    isLikeLoading: Boolean = false,
    toggleLike: () -> Unit = {},
    onChildCommentClick: (CommentItem) -> Unit
) {
    val commentData = comment.item

    Column(modifier = Modifier.fillMaxWidth()) {
        // 作者信息
        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            // 头像
            AsyncImage(
                model = commentData.author.avatarUrl,
                contentDescription = "头像",
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(
                verticalArrangement = Arrangement.Top,
                modifier = Modifier.fillMaxHeight()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 作者名
                    Text(
                        text = commentData.author.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.clickable {  }
                    )
                    if (replyingTo != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "回复",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = replyingTo.item.author.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            modifier = Modifier.clickable {  }
                        )
                    }
                }

                if (pinWebview) {
                    LocalPinnableContainer.current?.pin()
                }
                if (useWebview) {
                    WebviewComp() {
                        it.isVerticalScrollBarEnabled = false
                        it.isHorizontalScrollBarEnabled = false
                        it.loadZhihu(
                            "",
                            Jsoup.parse(commentData.content).processCommentImages(),
                            additionalStyle = """
                          body { margin: 0; }
                          p { margin: 0; margin-block: 0; }
                        """.trimIndent()
                        )
                    }
                } else {
                    // 评论内容
                    val content = remember(commentData.content) {
                        Jsoup.parse(commentData.content).body().text()
                    }
                    Text(
                        text = content,
                        fontSize = 14.sp,
                    )
                }
            }
        }

        // 底部信息栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 44.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 时间
            val formattedTime = remember(commentData.createdTime) {
                val time = commentData.createdTime * 1000
                val now = System.currentTimeMillis()
                val dateTime = Date(time)
                val nowDate = Date(now)

                when {
                    isSameDay(dateTime, nowDate) -> HMS.format(time)
                    isSameYear(dateTime, nowDate) -> MDHMS.format(time)
                    else -> YMDHMS.format(time)
                }
            }

            Text(
                text = formattedTime,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.weight(1f))

            // 回复按钮
            if (comment.clickTarget != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onChildCommentClick(comment) }
                ) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Outlined.Comment,
                        contentDescription = "回复",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = comment.item.childCommentCount.toString(),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                Spacer(modifier = Modifier.width(8.dp))
            }

            // 点赞
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(enabled = !isLikeLoading) { toggleLike() }
            ) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Outlined.ThumbUp,
                    contentDescription = "点赞",
                    modifier = Modifier.size(16.dp),
                    tint = if (isLiked)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = likeCount.toString(),
                    fontSize = 12.sp,
                    color = if (isLiked)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
        }
    }
}

fun Document.processCommentImages(): Document = apply {
    select("a.comment_img").forEach {
        it.tagName("img")
        it.text("")
        it.attr("src", it.attr("href"))
    }
}

private fun isSameDay(date1: Date, date2: Date): Boolean {
    val cal1 = Calendar.getInstance().apply { time = date1 }
    val cal2 = Calendar.getInstance().apply { time = date2 }
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

private fun isSameYear(date1: Date, date2: Date): Boolean {
    val cal1 = Calendar.getInstance().apply { time = date1 }
    val cal2 = Calendar.getInstance().apply { time = date2 }
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
}

@Preview(backgroundColor = 0xFFFFFF, showBackground = true, heightDp = 100)
@Composable
private fun CommentItemPreview() {
    val comment = CommentItem(
        item = DataHolder.Comment(
            id = "123",
            content = "<p>这是一条评论<br/>Lorem ipsum dolor sit amet, consectetur adipiscing elit. Vestibulum eleifend nisl vitae est tincidunt, non rhoncus magna cursus. Donec non elit non urna dignissim dapibus. Curabitur tempus magna quis dui pellentesque, in venenatis leo mollis. Duis ornare turpis in fermentum mollis. In at fringilla odio. Morbi elementum cursus purus, ut mollis libero facilisis ac. Sed eu mattis ante, ac aliquet purus. Quisque non eros ut ligula tincidunt elementum in ac sem. Praesent diam metus, bibendum vitae mollis ut, vehicula eget ante. Quisque efficitur, odio at ornare commodo, nibh dui eleifend enim, eget consequat quam tortor sit amet arcu. Aliquam mollis auctor ligula, placerat sodales leo malesuada eu. Donec porta nisl at congue laoreet. Duis vel tellus tincidunt, malesuada urna in, maximus nisl. Maecenas rhoncus augue eros, non aliquet eros eleifend ut. Mauris dignissim quis nisi id suscipit. In imperdiet, odio id ornare pretium, eros ipsum faucibus felis, at accumsan mi ex vitae mi.</p>",
            createdTime = System.currentTimeMillis() / 1000,
            author = DataHolder.Comment.Author(
                name = "作者",
                avatarUrl = "https://i1.hdslb.com/bfs/face/b93b6ff0c1d434ae8026a4bedc82d0d883b5da95.jpg",
                isOrg = false,
                type = "people",
                url = "",
                urlToken = "",
                id = "",
                headline = "个人介绍",
                avatarUrlTemplate = "",
                isAdvertiser = false,
                gender = 0,
                userType = ""
            ),
            likeCount = 10,
            childCommentCount = 5,
            type = "",
            url = "",
            resourceType = "",
            collapsed = false,
            top = false,
            isDelete = false,
            reviewing = false,
            isAuthor = false,
            canCollapse = false,
            childComments = emptyList(),
        ),
        clickTarget = null
    )
    val context = LocalContext.current
    CommentItem(
        comment,
        replyingTo = null,
        httpClient = AccountData.httpClient(context),
        useWebview = true,
        pinWebview = true
    ) {
    }
}
