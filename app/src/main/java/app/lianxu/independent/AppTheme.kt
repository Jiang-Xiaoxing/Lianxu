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
    val page=20.dp;val small=8.dp;val cluster=4.dp;val group=20.dp
    val action=48.dp;val radius=20.dp;val bubble=14.dp
}
internal val BrandTypeface=FontFamily(Font(R.font.lianxu_brand))
internal val BrandStyle=TextStyle(fontFamily=BrandTypeface,fontSize=30.sp,lineHeight=43.sp)
internal val PageTitleStyle=TextStyle(fontFamily=BrandTypeface,fontSize=24.sp,lineHeight=34.sp)
private val LightColors=lightColorScheme(
    primary=Color(0xFF07C160),onPrimary=Color.White,
    primaryContainer=Color(0xFFD8F5E5),onPrimaryContainer=Color(0xFF056B34),
    secondary=Color(0xFF4C7A5C),onSecondary=Color.White,
    secondaryContainer=Color(0xFFE7F8EE),onSecondaryContainer=Color(0xFF056B34),
    tertiary=Color(0xFF5C7370),onTertiary=Color.White,
    tertiaryContainer=Color(0xFFE8F0EE),onTertiaryContainer=Color(0xFF2C3F3C),
    background=Color(0xFFF7F7F7),onBackground=Color(0xFF191919),
    surface=Color(0xFFFFFFFF),onSurface=Color(0xFF191919),onSurfaceVariant=Color(0xFF888888),
    surfaceContainerLowest=Color(0xFFFFFFFF),surfaceContainerLow=Color(0xFFF7F7F7),surfaceContainer=Color(0xFFEDEDED),
    surfaceContainerHigh=Color(0xFFE5E5E5),surfaceContainerHighest=Color(0xFFD9D9D9),
    outline=Color(0xFFB2B2B2),outlineVariant=Color(0xFFE5E5E5),surfaceTint=Color.Transparent
)
private val DarkColors=darkColorScheme(
    primary=Color(0xFFD8BC80),onPrimary=Color(0xFF33280F),
    primaryContainer=Color(0xFF443B2A),onPrimaryContainer=Color(0xFFF0E4CC),
    secondary=Color(0xFFD0C3AC),onSecondary=Color(0xFF352F25),
    secondaryContainer=Color(0xFF443B2A),onSecondaryContainer=Color(0xFFE4C995),
    tertiary=Color(0xFFD5BBB0),onTertiary=Color(0xFF3B2B26),
    tertiaryContainer=Color(0xFF443630),onTertiaryContainer=Color(0xFFE8CDC1),
    background=Color(0xFF1B1B19),onBackground=Color(0xFFECE6DC),
    surface=Color(0xFF242420),onSurface=Color(0xFFECE6DC),onSurfaceVariant=Color(0xFFBDB5A8),
    surfaceContainerLowest=Color(0xFF191917),surfaceContainerLow=Color(0xFF1B1B19),surfaceContainer=Color(0xFF302F29),
    surfaceContainerHigh=Color(0xFF39372F),surfaceContainerHighest=Color(0xFF444137),
    outline=Color(0xFF9B9282),outlineVariant=Color(0xFF454238),surfaceTint=Color.Transparent
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
/** 角色资料列表图标：像微信那样各用一种柔和的颜色，不用绿色。 */
internal object ProfileIconColors {
    val edit=Color(0xFF5B8DEF)
    val memory=Color(0xFFF0A04B)
    val learning=Color(0xFF9A7FE3)
    val relationship=Color(0xFFEE6F6F)
    val companion=Color(0xFFF08DB5)
}
internal val Muted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant
internal val Line:Color @Composable get()=MaterialTheme.colorScheme.outlineVariant
internal val Outline:Color @Composable get()=MaterialTheme.colorScheme.outline
internal val Chrome:Color @Composable get()=MaterialTheme.colorScheme.surfaceContainerLow
internal val ChatBackground:Color @Composable get()=MaterialTheme.colorScheme.background
internal val SentBubble:Color @Composable get()=MaterialTheme.colorScheme.primaryContainer
internal val SelectedBackground:Color @Composable get()=MaterialTheme.colorScheme.secondaryContainer
internal val ChatPageBackground:Color @Composable get()=if(isSystemInDarkTheme())ChatBackground else Color(0xFFEDEDED)
internal val ChatBarBackground:Color @Composable get()=if(isSystemInDarkTheme())Chrome else Color(0xFFF7F7F7)
internal val ChatUserBubble:Color=Color(0xFF95EC69)
internal val ChatUserText:Color=Color(0xFF000000)
internal val ChatRoleBubble:Color @Composable get()=if(isSystemInDarkTheme())Paper else Color.White
internal val ChatRoleText:Color @Composable get()=if(isSystemInDarkTheme())Ink else Color(0xFF000000)
internal val ChatSendButton:Color=Color(0xFF07C160)
internal val ChatInputBorder:Color @Composable get()=if(isSystemInDarkTheme())Line else Color(0xFFD9D9D9)
@Composable internal fun freshButtonColors()=ButtonDefaults.buttonColors(containerColor=Accent,contentColor=MaterialTheme.colorScheme.onSecondaryContainer)
