package app.lianxu.independent

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

@Composable internal fun MoonCompanion(){
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val context=LocalContext.current
    val glow=remember{Animatable(1f)}
    LaunchedEffect(lifecycle){lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){
        val motion=android.provider.Settings.Global.getFloat(context.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)
        if(motion==0f){glow.snapTo(1f);return@repeatOnLifecycle}
        while(isActive){
            val enabled=android.provider.Settings.Global.getFloat(context.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f
            if(enabled){glow.animateTo(.65f,tween(2400));glow.animateTo(1f,tween(2400))}else glow.snapTo(1f)
            delay(100)
        }
    }}
    Image(painterResource(R.drawable.moon_companion),"星月相伴",Modifier.size(44.dp).alpha(glow.value))
}
