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
    secondary=Color(0xFF805719),onSecondary=Color.White,tertiary=Color(0xFF765B38),onTertiary=Color.White,
    primary=Color(0xFF805719),onPrimary=Color.White,
    primaryContainer=Color(0xFFF2D58B),onPrimaryContainer=Color(0xFF302B24),
    secondaryContainer=Color(0xFFE9C66D),onSecondaryContainer=Color(0xFF352A13),
    tertiaryContainer=Color(0xFFF7EED8),onTertiaryContainer=Color(0xFF805719),
    background=Color(0xFFF6F3EC),onBackground=Color(0xFF302B24),
    surface=Color.White,onSurface=Color(0xFF302B24),onSurfaceVariant=Color(0xFF6C655B),
    surfaceContainerLowest=Color.White,surfaceContainerLow=Color(0xFFF6F3EC),surfaceContainer=Color(0xFFEEE8DD),
    surfaceContainerHigh=Color(0xFFEEE8DD),surfaceContainerHighest=Color(0xFFE5DED2),
    outline=Color(0xFF948A7B),outlineVariant=Color(0xFFE7E0D4),surfaceTint=Color.Transparent
)
private val DarkColors=darkColorScheme(
    secondary=Color(0xFFE9C66D),onSecondary=Color(0xFF352A13),tertiary=Color(0xFFE2C19B),onTertiary=Color(0xFF362817),
    primary=Color(0xFFF2D58B),onPrimary=Color(0xFF382A0D),
    primaryContainer=Color(0xFF55431D),onPrimaryContainer=Color(0xFFF9E9BE),
    secondaryContainer=Color(0xFFDEC078),onSecondaryContainer=Color(0xFF32260E),
    tertiaryContainer=Color(0xFF3B3221),onTertiaryContainer=Color(0xFFE8CC87),
    background=Color(0xFF1B1915),onBackground=Color(0xFFEAE3D8),
    surface=Color(0xFF24211C),onSurface=Color(0xFFEAE3D8),onSurfaceVariant=Color(0xFFC7BFAF),
    surfaceContainerLowest=Color(0xFF1B1915),surfaceContainerLow=Color(0xFF201D18),surfaceContainer=Color(0xFF302B23),
    surfaceContainerHigh=Color(0xFF383127),surfaceContainerHighest=Color(0xFF40372B),
    outline=Color(0xFFA29886),outlineVariant=Color(0xFF4C4335),surfaceTint=Color.Transparent
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
