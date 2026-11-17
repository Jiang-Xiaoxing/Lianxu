package app.lianxu.independent

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun ChatPicture(bytes:ByteArray,modifier:Modifier=Modifier,description:String="图片",contentScale:ContentScale=ContentScale.Fit){
    val bitmap by produceState<android.graphics.Bitmap?>(null,bytes){value=withContext(Dispatchers.IO){BitmapFactory.decodeByteArray(bytes,0,bytes.size)}}
    bitmap?.let{Image(it.asImageBitmap(),description,modifier,contentScale=contentScale)}
}

@Composable internal fun PendingPicture(image:PendingChatImage,expanded:Boolean,onExpand:()->Unit,onView:()->Unit,onRemove:()->Unit){
    val size by animateDpAsState(if(expanded)88.dp else 34.dp,tween(if(expanded)350 else 300,easing=CubicBezierEasing(.0f,.0f,.2f,1f)),label="pending-picture-size")
    Box(Modifier.fillMaxWidth().padding(start=56.dp,top=8.dp,bottom=4.dp).testTag(if(expanded)"pending-picture-expanded"else"pending-picture-collapsed")){
        Box(Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)){
            ChatPicture(image.bytes,Modifier.size(size).clip(RoundedCornerShape(8.dp)).border(1.dp,Paper,RoundedCornerShape(8.dp)).clickable(role=Role.Button){if(expanded)onView()else onExpand()}.testTag("pending-picture"),"待发送图片，点击${if(expanded)"放大"else"展开"}",ContentScale.Crop)
            if(expanded)IconButton(onClick=onRemove,modifier=Modifier.align(Alignment.TopEnd).offset(16.dp,(-16).dp).size(48.dp).testTag("remove-picture").semantics{contentDescription="移除待发送图片"}){
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(50)).background(Ink),contentAlignment=Alignment.Center){Text("×",color=Paper,style=MaterialTheme.typography.labelLarge)}
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun MessagePicture(vm:AppState,ref:ChatImageRef,onControl:(()->Unit)?=null,onView:(ByteArray)->Unit){
    val loaded by produceState<Result<ByteArray>?>(null,ref){value=runCatching{vm.processing.read{db->ChatImages.read(db,ref)}}}
    val bytes=loaded?.getOrNull()
    if(bytes!=null)ChatPicture(bytes,Modifier.sizeIn(maxWidth=220.dp,maxHeight=220.dp).width(180.dp).aspectRatio(ref.width.toFloat()/ref.height).clip(RoundedCornerShape(12.dp)).combinedClickable(role=Role.Button,onClick={onView(bytes)},onLongClick=onControl).testTag("message-image-${ref.messageId}"),"消息图片，点击放大，长按管理使用方式")
    else if(loaded==null)Box(Modifier.size(180.dp),contentAlignment=Alignment.Center){Text("正在读取图片…",style=MaterialTheme.typography.bodySmall,color=Muted)}
    else Text("图片暂时无法读取",style=MaterialTheme.typography.bodySmall,color=Muted)
}

@Composable internal fun ChatPictureViewer(bytes:ByteArray,onClose:()->Unit){
    var scale by remember(bytes){mutableFloatStateOf(1f)};var x by remember(bytes){mutableFloatStateOf(0f)};var y by remember(bytes){mutableFloatStateOf(0f)}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize().testTag("image-viewer"),color=Paper){
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp))){
                ChatPicture(bytes,Modifier.fillMaxSize().padding(16.dp).pointerInput(bytes){detectTransformGestures{_,pan,zoom,_->scale=(scale*zoom).coerceIn(1f,5f);x=if(scale==1f)0f else (x+pan.x).coerceIn(-size.width*(scale-1)/2,size.width*(scale-1)/2);y=if(scale==1f)0f else (y+pan.y).coerceIn(-size.height*(scale-1)/2,size.height*(scale-1)/2)}}.graphicsLayer{scaleX=scale;scaleY=scale;translationX=x;translationY=y},"图片，可双指缩放和拖动")
                TextButton(onClick=onClose,modifier=Modifier.align(Alignment.TopEnd).statusBarsPadding().testTag("image-viewer-close")){Text("关闭")}
            }
        }
    }
}
