package app.lianxu.independent

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object UiSpace {
    val page=16.dp;val small=8.dp;val cluster=4.dp;val group=16.dp
    val action=48.dp;val radius=18.dp;val bubble=12.dp
}
internal val BrandTypeface=FontFamily(Font(R.font.lianxu_brand))
internal val BrandStyle=TextStyle(fontFamily=BrandTypeface,fontSize=30.sp,lineHeight=43.sp)
private val LightColors=lightColorScheme(
    primary=Color(0xFF007A37),onPrimary=Color.White,
    primaryContainer=Color(0xFF95EC69),onPrimaryContainer=Color(0xFF191F1B),
    secondaryContainer=Color(0xFF07C160),onSecondaryContainer=Color(0xFF10271A),
    tertiaryContainer=Color(0xFFECFCEE),onTertiaryContainer=Color(0xFF007A37),
    background=Color(0xFFF5F5F5),onBackground=Color(0xFF191F1B),
    surface=Color.White,onSurface=Color(0xFF191F1B),onSurfaceVariant=Color(0xFF606862),
    surfaceContainerLowest=Color.White,surfaceContainerLow=Color(0xFFF5F5F5),surfaceContainer=Color(0xFFE8EAE9),
    surfaceContainerHigh=Color(0xFFE8EAE9),surfaceContainerHighest=Color(0xFFE1E4E2),
    outline=Color(0xFF8A938C),outlineVariant=Color(0xFFE2E5E3),surfaceTint=Color.Transparent
)
private val DarkColors=darkColorScheme(
    primary=Color(0xFF95EC69),onPrimary=Color(0xFF173818),
    primaryContainer=Color(0xFF355C30),onPrimaryContainer=Color(0xFFE9F5E6),
    secondaryContainer=Color(0xFF63D78B),onSecondaryContainer=Color(0xFF092111),
    tertiaryContainer=Color(0xFF233B29),onTertiaryContainer=Color(0xFFB4E6B1),
    background=Color(0xFF141815),onBackground=Color(0xFFE1E8E2),
    surface=Color(0xFF1E2420),onSurface=Color(0xFFE1E8E2),onSurfaceVariant=Color(0xFFB8C3BA),
    surfaceContainerLowest=Color(0xFF141815),surfaceContainerLow=Color(0xFF171C18),surfaceContainer=Color(0xFF29312B),
    surfaceContainerHigh=Color(0xFF303932),surfaceContainerHighest=Color(0xFF354038),
    outline=Color(0xFF89988D),outlineVariant=Color(0xFF3B493F),surfaceTint=Color.Transparent
)
@Composable internal fun LianxuTheme(dark:Boolean=isSystemInDarkTheme(),content:@Composable ()->Unit){
    MaterialTheme(colorScheme=if(dark)DarkColors else LightColors,typography=Typography(
        titleLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=22.sp,lineHeight=30.sp,fontWeight=FontWeight.Medium),
        titleMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=18.sp,lineHeight=24.sp,fontWeight=FontWeight.Medium),
        titleSmall=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium),
        bodyLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=16.sp,lineHeight=25.sp),
        bodyMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=14.sp,lineHeight=22.sp),
        bodySmall=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=12.sp,lineHeight=18.sp),
        labelLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium),
        labelMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=12.sp,lineHeight=18.sp),
        labelSmall=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=11.sp,lineHeight=16.sp)
    ),content=content)
}
internal val Paper:Color @Composable get()=MaterialTheme.colorScheme.surface
internal val Ink:Color @Composable get()=MaterialTheme.colorScheme.onSurface
internal val Accent:Color @Composable get()=MaterialTheme.colorScheme.secondaryContainer
internal val GreenText:Color @Composable get()=MaterialTheme.colorScheme.primary
internal val Muted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant
internal val Line:Color @Composable get()=MaterialTheme.colorScheme.outlineVariant
internal val Outline:Color @Composable get()=MaterialTheme.colorScheme.outline
internal val Chrome:Color @Composable get()=MaterialTheme.colorScheme.surfaceContainerLow
internal val ChatBackground:Color @Composable get()=MaterialTheme.colorScheme.background
internal val SentBubble:Color @Composable get()=MaterialTheme.colorScheme.primaryContainer
internal val SelectedBackground:Color @Composable get()=MaterialTheme.colorScheme.tertiaryContainer
@Composable internal fun freshButtonColors()=ButtonDefaults.buttonColors(containerColor=Accent,contentColor=MaterialTheme.colorScheme.onSecondaryContainer)
