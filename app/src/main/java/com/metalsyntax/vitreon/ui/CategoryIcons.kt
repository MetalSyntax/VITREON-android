package com.metalsyntax.vitreon.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

/** Los 70 íconos de categoría de la web (Material Symbols de CategoryModal.tsx) con su equivalente en Compose. */
val CATEGORY_ICONS: Map<String, ImageVector> = linkedMapOf(
    "work" to Icons.Outlined.Work, "person" to Icons.Outlined.Person, "lightbulb" to Icons.Outlined.Lightbulb, "flight" to Icons.Outlined.Flight,
    "fitness_center" to Icons.Outlined.FitnessCenter, "description" to Icons.Outlined.Description, "shopping_cart" to Icons.Outlined.ShoppingCart,
    "favorite" to Icons.Outlined.FavoriteBorder, "home" to Icons.Outlined.Home, "star" to Icons.Outlined.StarOutline, "school" to Icons.Outlined.School,
    "restaurant" to Icons.Outlined.Restaurant, "payments" to Icons.Outlined.Payments, "medical_services" to Icons.Outlined.MedicalServices,
    "directions_car" to Icons.Outlined.DirectionsCar, "celebration" to Icons.Outlined.Celebration, "palette" to Icons.Outlined.Palette,
    "menu_book" to Icons.Outlined.MenuBook, "pets" to Icons.Outlined.Pets, "camera_alt" to Icons.Outlined.CameraAlt, "eco" to Icons.Outlined.Eco,
    "savings" to Icons.Outlined.Savings, "translate" to Icons.Outlined.Translate, "code" to Icons.Outlined.Code, "smart_toy" to Icons.Outlined.SmartToy,
    "sports_esports" to Icons.Outlined.SportsEsports, "theater_comedy" to Icons.Outlined.TheaterComedy, "monitoring" to Icons.Outlined.Insights,
    "history_edu" to Icons.Outlined.HistoryEdu, "construction" to Icons.Outlined.Construction, "volunteer_activism" to Icons.Outlined.VolunteerActivism,
    "rocket_launch" to Icons.Outlined.RocketLaunch, "terminal" to Icons.Outlined.Terminal, "vpn_key" to Icons.Outlined.VpnKey, "shield" to Icons.Outlined.Shield,
    "lock" to Icons.Outlined.Lock, "search" to Icons.Outlined.Search, "notifications" to Icons.Outlined.Notifications, "settings" to Icons.Outlined.Settings,
    "cloud" to Icons.Outlined.Cloud, "folder" to Icons.Outlined.Folder, "attach_file" to Icons.Outlined.AttachFile, "mail" to Icons.Outlined.Mail,
    "inventory_2" to Icons.Outlined.Inventory2, "archive" to Icons.Outlined.Archive, "shopping_bag" to Icons.Outlined.ShoppingBag,
    "storefront" to Icons.Outlined.Storefront, "coffee" to Icons.Outlined.Coffee, "local_bar" to Icons.Outlined.LocalBar, "lunch_dining" to Icons.Outlined.LunchDining,
    "self_improvement" to Icons.Outlined.SelfImprovement, "directions_run" to Icons.Outlined.DirectionsRun, "pool" to Icons.Outlined.Pool,
    "sports_basketball" to Icons.Outlined.SportsBasketball, "music_note" to Icons.Outlined.MusicNote, "movie" to Icons.Outlined.Movie, "tv" to Icons.Outlined.Tv,
    "radio" to Icons.Outlined.Radio, "headphones" to Icons.Outlined.Headphones, "videogame_asset" to Icons.Outlined.VideogameAsset,
    "photo_camera" to Icons.Outlined.PhotoCamera, "camera_roll" to Icons.Outlined.CameraRoll, "auto_stories" to Icons.Outlined.AutoStories,
    "extension" to Icons.Outlined.Extension, "map" to Icons.Outlined.Map, "explore" to Icons.Outlined.Explore, "public" to Icons.Outlined.Public,
    "language" to Icons.Outlined.Language, "account_balance_wallet" to Icons.Outlined.AccountBalanceWallet, "receipt_long" to Icons.Outlined.ReceiptLong,
)

fun categoryIcon(name: String): ImageVector = CATEGORY_ICONS[name] ?: when (name) {
    "box", "envelope" -> Icons.Outlined.Inventory2
    else -> Icons.Outlined.Folder
}
