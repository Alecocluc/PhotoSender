package com.appharbor.pherry.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.appharbor.pherry.R

/**
 * Phosphor icons (MIT, v2.0.8) shipped as vector drawables. One family across the phone and the
 * desktop. `*Fill` variants mark the selected navigation destination.
 */
object Ph {
    val House = R.drawable.ph_house_simple
    val HouseFill = R.drawable.ph_house_simple_fill
    val Images = R.drawable.ph_images_square
    val ImagesFill = R.drawable.ph_images_square_fill
    val Transfers = R.drawable.ph_arrows_left_right
    val TransfersFill = R.drawable.ph_arrows_left_right_fill
    val Gear = R.drawable.ph_gear_six
    val GearFill = R.drawable.ph_gear_six_fill
    val Desktop = R.drawable.ph_desktop
    val Phone = R.drawable.ph_device_mobile
    val QrCode = R.drawable.ph_qr_code
    val Scan = R.drawable.ph_scan
    val Wifi = R.drawable.ph_wifi_high
    val WifiSlash = R.drawable.ph_wifi_slash
    val Refresh = R.drawable.ph_arrows_clockwise
    val Upload = R.drawable.ph_upload_simple
    val Send = R.drawable.ph_paper_plane_tilt
    val Check = R.drawable.ph_check
    val CheckBold = R.drawable.ph_check_bold
    val CheckCircle = R.drawable.ph_check_circle
    val CheckCircleFill = R.drawable.ph_check_circle_fill
    val X = R.drawable.ph_x
    val XBold = R.drawable.ph_x_bold
    val Warning = R.drawable.ph_warning
    val WarningCircle = R.drawable.ph_warning_circle
    val WarningCircleFill = R.drawable.ph_warning_circle_fill
    val Info = R.drawable.ph_info
    val Trash = R.drawable.ph_trash
    val Search = R.drawable.ph_magnifying_glass
    val CaretRight = R.drawable.ph_caret_right
    val CaretRightBold = R.drawable.ph_caret_right_bold
    val CaretDown = R.drawable.ph_caret_down
    val ArrowLeft = R.drawable.ph_arrow_left
    val ArrowRight = R.drawable.ph_arrow_right
    val Play = R.drawable.ph_play
    val Pause = R.drawable.ph_pause
    val Stop = R.drawable.ph_stop
    val Image = R.drawable.ph_image
    val Video = R.drawable.ph_video_camera
    val File = R.drawable.ph_file
    val FilmStrip = R.drawable.ph_film_strip
    val Key = R.drawable.ph_key
    val Moon = R.drawable.ph_moon
    val Sun = R.drawable.ph_sun
    val Monitor = R.drawable.ph_monitor
    val Link = R.drawable.ph_link
    val Plugs = R.drawable.ph_plugs_connected
    val Plug = R.drawable.ph_plug
    val Lightning = R.drawable.ph_lightning
    val BatteryCharging = R.drawable.ph_battery_charging
    val Clock = R.drawable.ph_clock
    val History = R.drawable.ph_clock_counter_clockwise
    val SelectAll = R.drawable.ph_selection_all
    val Camera = R.drawable.ph_camera
    val Share = R.drawable.ph_share_network
    val Eye = R.drawable.ph_eye
    val SealCheck = R.drawable.ph_seal_check
    val ShieldCheck = R.drawable.ph_shield_check
    val Lock = R.drawable.ph_lock_simple
    val Hourglass = R.drawable.ph_hourglass_medium
    val CloudSlash = R.drawable.ph_cloud_slash
    val Calendar = R.drawable.ph_calendar_blank
    val Sparkle = R.drawable.ph_sparkle
    val Dots = R.drawable.ph_dots_three
    val Copy = R.drawable.ph_copy
    val Undo = R.drawable.ph_arrow_counter_clockwise
    val Question = R.drawable.ph_question
    val Ticket = R.drawable.ph_ticket
    val Stamp = R.drawable.ph_stamp
    val Folder = R.drawable.ph_folder_simple
    val Eye2 = R.drawable.ph_eye
}

/** A Phosphor icon at the standard 24dp (or [size]). */
@Composable
fun PhIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}
