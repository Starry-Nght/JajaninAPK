 package com.jajanin.app.feature.seller

import com.jajanin.app.MainActivity
import com.jajanin.app.feature.buyer.*
import com.jajanin.app.feature.seller.*
import com.jajanin.app.feature.developer.*
import com.jajanin.app.feature.chat.openConversationById
import com.jajanin.app.feature.chat.startChatObserver
import com.jajanin.app.feature.inbox.*

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.jajanin.app.core.constants.LOCATION_OPTIONS
import com.jajanin.app.core.constants.STATUS_CANCELLED
import com.jajanin.app.core.constants.STATUS_COMPLETED
import com.jajanin.app.core.constants.STATUS_CONFIRMED
import com.jajanin.app.core.constants.STATUS_PENDING
import com.jajanin.app.core.constants.STATUS_PREPARING
import com.jajanin.app.core.constants.STATUS_READY
import com.jajanin.app.core.navigation.Screen
import com.jajanin.app.core.ui.ProductImageCache
import com.jajanin.app.core.ui.isTerminalStatus
import com.jajanin.app.core.ui.showThemedInfo
import com.jajanin.app.core.ui.showThemedOptions
import com.jajanin.app.core.util.formatRupiah
import com.jajanin.app.data.model.ProductRow
import com.jajanin.app.feature.buyer.home.normalizeCategory
import com.jajanin.app.data.repository.AdminOrder
import com.jajanin.app.data.repository.DailyResetResult
import com.jajanin.app.data.repository.DevAnnouncementRow
import com.jajanin.app.data.repository.DevStats
import com.jajanin.app.data.repository.DevUser
import com.jajanin.app.data.repository.DevWarningRow
import com.jajanin.app.data.repository.DeveloperRepository
import com.jajanin.app.data.repository.OrderObserver
import com.jajanin.app.data.repository.OrderRepository
import com.jajanin.app.data.repository.PhoneRepository
import com.jajanin.app.data.repository.PlacedOrder
import com.jajanin.app.data.repository.ProductDraft
import com.jajanin.app.data.repository.RegisterResult
import com.jajanin.app.data.repository.SellerInvite
import com.jajanin.app.data.repository.StallInfo
import com.jajanin.app.data.repository.SupabaseRepository
import com.jajanin.app.feature.buyer.exp.EXP_MAX_LEVEL
import com.jajanin.app.feature.buyer.exp.ExpEntry
import com.jajanin.app.feature.buyer.exp.ExpRepository
import com.jajanin.app.feature.buyer.exp.expLevel
import com.jajanin.app.feature.buyer.exp.expNextAt
import com.jajanin.app.notifications.NotificationHelper
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.realtime.PostgresAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import com.jajanin.app.R

internal fun MainActivity.showProductForm() {
    val self = this
    if (!SupabaseRepository.hasSession()) {
        Toast.makeText(this, "Masuk sebagai penjual untuk menambah produk.", Toast.LENGTH_LONG).show()
        return
    }
    authScope.launch {
        val stallId = OrderRepository.sellerStallId()
        if (stallId == null) {
            Toast.makeText(self, "Lengkapi nama warung dulu.", Toast.LENGTH_LONG).show()
            ensureSellerStallName { openProductForm(null) }
            return@launch
        }
        openProductForm(null)
    }
}

internal fun MainActivity.showProductEditForm(productId: String) {
    val product = ui.sellerProducts.firstOrNull { it.id == productId }
    if (product == null) {
        Toast.makeText(this, "Produk belum termuat. Coba muat ulang daftar produk.", Toast.LENGTH_SHORT).show()
        refreshSellerProducts()
        return
    }
    openProductForm(product)
}

private const val PREFS_SELLER_ROOMS = "jajanin_recent_rooms"

internal fun MainActivity.loadRecentSellerRooms(): List<String> {
    val uid = SupabaseRepository.currentUid().orEmpty()
    val prefs = getSharedPreferences(PREFS_SELLER_ROOMS, Context.MODE_PRIVATE)
    val raw = prefs.getString("rooms:$uid", "").orEmpty()
    val list = raw.split("||").map { it.trim() }.filter { it.isNotBlank() }.distinct().take(4)
    ui.recentSellerRooms = list
    return list
}

internal fun MainActivity.recordRecentSellerRoom(room: String) {
    val clean = room.trim()
    if (clean.isBlank()) return
    val uid = SupabaseRepository.currentUid().orEmpty()
    val updated = (listOf(clean) + loadRecentSellerRooms())
        .distinctBy { it.lowercase() }
        .take(4)
    ui.recentSellerRooms = updated
    getSharedPreferences(PREFS_SELLER_ROOMS, Context.MODE_PRIVATE)
        .edit()
        .putString("rooms:$uid", updated.joinToString("||"))
        .apply()
}

/** Buka halaman form produk (geser, bukan dialog). */
internal fun MainActivity.openProductForm(existing: ProductRow?) {
    val recent = loadRecentSellerRooms()
    val fallbackRoom = recent.firstOrNull() ?: ui.myStallRoom
    if (existing != null) {
        ui.productEditId = existing.id
        ui.productName = existing.name
        ui.productDescription = existing.description
        ui.productCategory = normalizeCategory(existing.category)
        ui.productPrice = existing.priceIdr.toString()
        ui.productStock = existing.stock?.toString().orEmpty()
        ui.productImageUrl = existing.imageUrl.orEmpty()
        ui.productIngredients = existing.cleanIngredients
        ui.productRoom = existing.pickupRoom.ifBlank { fallbackRoom }
        ui.productAvailable = existing.available
        ui.productOrderSchedule = existing.orderSchedule
        ui.productPickupSessions = LinkedHashSet(existing.parsedPickupSessions)
    } else {
        ui.productEditId = null
        ui.productName = ""
        ui.productDescription = ""
        ui.productCategory = "Food"
        ui.productPrice = ""
        ui.productStock = ""
        ui.productImageUrl = ""
        ui.productIngredients = ""
        ui.productRoom = fallbackRoom
        ui.productAvailable = true
        ui.productOrderSchedule = "both"
        ui.productPickupSessions = linkedSetOf("Istirahat 1", "Istirahat 2", "Pulang")
    }
    ui.productSaving = false
    ui.productError = ""
    ui.go(Screen.PRODUCT_FORM)
}

/** Input satu baris form produk lewat dialog fokus (judul + keyboard sesuai). */
internal fun MainActivity.showProductTextField(field: String) {
    // title, hint, initial, numeric, maxLen, multiline
    val spec = when (field) {
        "harga" -> arrayOf("Harga", "cth. 12000", ui.productPrice, "1", "9", "0")
        "stok" -> arrayOf("Stok", "Kosongkan = tanpa batas", ui.productStock, "1", "5", "0")
        "deskripsi" -> arrayOf("Deskripsi", "Singkat saja", ui.productDescription, "0", "200", "1")
        "bahan" -> arrayOf("Bahan", "Opsional", ui.productIngredients, "0", "120", "1")
        "ruangan" -> arrayOf("Ambil di Ruangan", "cth. Ruang XII RPL 1 / Kantin Utama", ui.productRoom, "0", "45", "0")
        else -> arrayOf("Nama produk", "cth. Nasi goreng", ui.productName, "0", "60", "0")
    }
    val title = spec[0]
    val hintText = spec[1]
    val initial = spec[2]
    val numeric = spec[3] == "1"
    val multiline = spec[5] == "1"
    val input = EditText(this).apply {
        setSingleLine(!multiline)
        if (multiline) {
            minLines = 2
            gravity = android.view.Gravity.TOP
        }
        if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(android.text.InputFilter.LengthFilter(spec[4].toInt()))
        setText(initial)
        setSelection(initial.length)
        hint = hintText
    }
    android.app.AlertDialog.Builder(this).setTitle(title)
        .setView(input)
        .setPositiveButton("OK") { _, _ ->
            val value = input.text.toString().trim()
            when (field) {
                "harga" -> ui.productPrice = value.filter(Char::isDigit)
                "stok" -> ui.productStock = value.filter(Char::isDigit)
                "deskripsi" -> ui.productDescription = value
                "bahan" -> ui.productIngredients = value
                "ruangan" -> ui.productRoom = value
                else -> ui.productName = value
            }
            ui.productError = ""
            ui.invalidate()
        }
        .setNegativeButton("Batal", null)
        .show()
    input.requestFocus()
    input.post {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }
}

/** Pilih kategori lewat opsi bertema. */
internal fun MainActivity.showProductCategoryPicker() {
    val choices = listOf("Food", "Drinks")
    showThemedOptions("Kategori", choices, choices.indexOf(ui.productCategory).coerceAtLeast(0)) { which ->
        ui.productCategory = choices[which]
        ui.invalidate()
    }
}

/** Saklar tampil di etalase dari halaman form. */
internal fun MainActivity.toggleProductFormAvailable() {
    ui.productAvailable = !ui.productAvailable
    ui.invalidate()
}

/** Validasi + simpan dari halaman form (error tampil inline). */
internal fun MainActivity.saveProductForm() {
    if (ui.productSaving || ui.productImageUploading) return
    if (ui.productImageUploading) {
        ui.productError = "Tunggu gambar selesai diunggah."
        ui.invalidate()
        return
    }
    val priceValue = ui.productPrice.filter(Char::isDigit).toIntOrNull()
    val stockText = ui.productStock.trim()
    ui.productError = when {
        ui.productName.isBlank() -> "Nama produk wajib diisi."
        ui.productRoom.isBlank() -> "Ruangan pengambilan (Ambil di ruangan) wajib diisi."
        priceValue == null || priceValue <= 0 -> "Harga wajib angka lebih dari Rp 0."
        priceValue > 999_999_999 -> "Harga terlalu besar."
        stockText.isNotBlank() && (stockText.toIntOrNull() == null || stockText.toInt() < 0) ->
            "Stok harus angka 0 ke atas, atau kosongkan."
        else -> ""
    }
    if (ui.productError.isNotBlank()) {
        ui.invalidate()
        return
    }
    ui.productSaving = true
    ui.invalidate()
    saveProduct(null)
}

internal fun MainActivity.pickProductImage() {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
    }
    startActivityForResult(intent, REQUEST_PRODUCT_IMAGE)
}

/** Upload gambar produk pilihan ke Storage publik lalu simpan URL-nya. */
internal fun MainActivity.uploadProductImage(uri: android.net.Uri) {
    val self = this
    ui.productImageUploading = true
    ui.invalidate()
    authScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Gambar tidak bisa dibaca.")
            // Kecilkan dulu di HP (maks 1280px, JPEG q82): upload 10-50x
            // lebih ringan daripada foto mentah, jarang gagal/timeout.
            val bytes = compressForUpload(raw)
            require(bytes.size <= 5 * 1024 * 1024) { "Ukuran gambar maksimal 5MB." }
            val token = SupabaseRepository.currentAccessToken()
                ?: error("Sesi habis, masuk ulang.")
            val uid = SupabaseRepository.currentUid() ?: error("Sesi habis, masuk ulang.")
            val path = "$uid/${java.util.UUID.randomUUID()}.jpg"
            val url = java.net.URL("${SupabaseRepository.baseUrl}/storage/v1/object/product-images/$path")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                setFixedLengthStreamingMode(bytes.size)
                setRequestProperty("apikey", SupabaseRepository.publishableKey)
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "image/jpeg")
                setRequestProperty("x-upsert", "true")
                connectTimeout = 15000
                readTimeout = 15000
            }
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) error("Unggah gagal (HTTP $code).")
            val publicUrl = "${SupabaseRepository.baseUrl}/storage/v1/object/public/product-images/$path"
            launch(kotlinx.coroutines.Dispatchers.Main) {
                ui.productImageUrl = publicUrl
                ui.productImageUploading = false
                ui.invalidate()
                            Toast.makeText(self, "Gambar terunggah.", Toast.LENGTH_SHORT).show()
            }
        } catch (error: Throwable) {
            launch(kotlinx.coroutines.Dispatchers.Main) {
                ui.productImageUploading = false
                ui.invalidate()
                            Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * Decode secukupnya (sisi terpanjang [maxEdge]) + putar sesuai EXIF +
 * kompres JPEG. Foto 12MP (~4-8MB) jadi ~150-400KB tanpa beda kasat mata
 * di ubin menu. Gagal decode = kembalikan bytes asli.
 */
internal fun MainActivity.compressForUpload(raw: ByteArray, maxEdge: Int = 1280, quality: Int = 82): ByteArray {
    return runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return raw
        var sample = 1
        while (w / sample > maxEdge || h / sample > maxEdge) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            ?: return raw
        val edge = maxOf(bmp.width, bmp.height)
        if (edge > maxEdge) {
            val scale = maxEdge.toFloat() / edge
            val scaled = android.graphics.Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1), true,
            )
            if (scaled != bmp) bmp.recycle()
            bmp = scaled
        }
        val oriented = applyExifOrientation(raw, bmp)
        val out = java.io.ByteArrayOutputStream()
        oriented.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
        if (oriented != bmp) oriented.recycle()
        bmp.recycle()
        out.toByteArray().takeIf { it.isNotEmpty() } ?: raw
    }.getOrDefault(raw)
}

/** Putar bitmap sesuai tag EXIF Orientation (foto kamera sering miring). */
internal fun MainActivity.applyExifOrientation(raw: ByteArray, bmp: android.graphics.Bitmap): android.graphics.Bitmap {
    val orientation = runCatching {
        android.media.ExifInterface(java.io.ByteArrayInputStream(raw))
            .getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )
    }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
    val matrix = android.graphics.Matrix()
    when (orientation) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        else -> return bmp
    }
    return runCatching {
        android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }.getOrDefault(bmp)
}

internal fun MainActivity.loadProductImage(url: String) {
    if (ProductImageCache.images.containsKey(url)) return
    authScope.launch(Dispatchers.IO) {
        val bitmap = runCatching {
            val connection = java.net.URL(url).openConnection().apply { connectTimeout = 6000; readTimeout = 6000 }
            val raw = connection.getInputStream().use { it.readBytes() }
            // Ubin menu kecil: decode secukupnya (maks 800px) agar hemat RAM.
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 800 || bounds.outHeight / sample > 800) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        }.getOrNull()
        if (bitmap != null) {
            ProductImageCache.images[url] = bitmap
            launch(Dispatchers.Main) { ui.invalidate() }
        }
    }
}

internal fun MainActivity.saveProduct(dialog: Dialog? = null) {
    val self = this
    if (ui.productImageUploading) {
        ui.productError = "Tunggu gambar selesai diunggah."
        ui.productSaving = false
        ui.invalidate()
        return
    }
    val price = ui.productPrice.filter(Char::isDigit).toIntOrNull()
    val stock = ui.productStock.filter(Char::isDigit).toIntOrNull()
    if (price == null) {
        ui.productError = "Harga tidak valid."
        ui.productSaving = false
        ui.invalidate()
        return
    }
    authScope.launch {
        try {
            val imageUrl = ui.productImageUrl.takeIf { it.startsWith("https://") }
            val packedIngredients = com.jajanin.app.data.model.packRoomAndIngredients(ui.productRoom, ui.productIngredients)
            val draft = ProductDraft(
                name = ui.productName,
                description = ui.productDescription,
                category = ui.productCategory,
                priceIdr = price,
                imageUrl = imageUrl,
                stock = stock,
                ingredients = packedIngredients,
                available = ui.productAvailable,
                orderSchedule = ui.productOrderSchedule,
                pickupSessions = ui.productPickupSessions.joinToString(",").ifBlank { "Istirahat 1,Istirahat 2,Pulang" },
            )
            val editingId = ui.productEditId
            if (editingId == null) {
                OrderRepository.createProduct(draft)
            } else {
                OrderRepository.updateProduct(editingId, draft)
                ui.productEditId = null
            }
            val cleanRoom = ui.productRoom.trim()
            if (cleanRoom.isNotBlank()) {
                ui.myStallRoom = cleanRoom
                recordRecentSellerRoom(cleanRoom)
                ui.myStallId?.let { OrderRepository.updateStallLocation(it, cleanRoom) }
            }
            dialog?.dismiss()
            ui.productSaving = false
            ui.productError = ""
            refreshProducts()
            refreshSellerProducts()
            Toast.makeText(self, "Produk berhasil disimpan.", Toast.LENGTH_SHORT).show()
            if (dialog == null && ui.currentScreen == Screen.PRODUCT_FORM) ui.goBack()
        } catch (error: Throwable) {
            ui.productSaving = false
            ui.productError = SupabaseRepository.errorMessage(error)
            ui.invalidate()
            Toast.makeText(self, ui.productError, Toast.LENGTH_LONG).show()
        }
    }
}

/** Konfirmasi lalu hapus produk milik penjual. */
internal fun MainActivity.confirmDeleteProduct(productId: String) {
    val self = this
    // Jangan bergantung pada sellerProducts: daftar bisa sedang dimuat ulang
    // atau form dibuka dari alur lain. Tetap konfirmasi walau nama belum ada.
    val product = ui.sellerProducts.firstOrNull { it.id == productId }
    val label = product?.name?.let { "\"$it\"" } ?: "Produk ini"
    showThemedInfo(
        title = "Hapus produk?",
        message = "$label akan dihapus permanen dan tidak lagi tampil di beranda pembeli.",
        posLabel = "Hapus",
        destructivePos = true,
        onPos = {
            authScope.launch {
                try {
                    OrderRepository.deleteProduct(productId)
                    // Optimis: hilangkan dari kedua daftar sebelum refresh server.
                    ui.sellerProducts = ui.sellerProducts.filterNot { it.id == productId }
                    ui.products = ui.products.filterNot { it.id == productId }
                    if (ui.productEditId == productId) ui.productEditId = null
                    ui.invalidate()
                    refreshProducts()
                    refreshSellerProducts()
                    Toast.makeText(self, "Produk dihapus.", Toast.LENGTH_SHORT).show()
                    if (ui.currentScreen == Screen.PRODUCT_FORM) ui.goBack()
                } catch (error: Throwable) {
                    val errStr = SupabaseRepository.errorMessage(error)
                    showThemedInfo(
                        title = "Gagal menghapus",
                        message = errStr,
                        posLabel = "Mengerti",
                    )
                    refreshSellerProducts()
                }
            }
        },
        negLabel = "Batal",
    )
}

internal fun MainActivity.refreshSellerProducts() {
    val self = this
    authScope.launch {
        runCatching { OrderRepository.fetchSellerProducts() }
            .onSuccess { products -> ui.sellerProducts = products; ui.invalidate() }
            .onFailure { error -> Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show() }
    }
}

internal fun MainActivity.toggleProductAvailability(productId: String) {
    val self = this
    val product = ui.sellerProducts.firstOrNull { it.id == productId } ?: return
    // Optimis: pill "Aktif/Mati" langsung berpindah; server mengonfirmasi lewat refresh.
    val optimistic = !product.available
    ui.sellerProducts = ui.sellerProducts.map { if (it.id == productId) it.copy(available = optimistic) else it }
    ui.invalidate()
    authScope.launch {
        try {
            OrderRepository.setProductAvailable(productId, optimistic)
            refreshSellerProducts()
            refreshProducts()
        } catch (error: Throwable) {
            Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            refreshSellerProducts()
        }
    }
}

private fun MainActivity.patchAdminOrderLocal(
    orderId: String,
    status: String? = null,
    paymentStatus: String? = null,
    cancelReason: String? = null,
) {
    ui.adminOrders = ui.adminOrders.map { order ->
        if (order.id == orderId) {
            order.copy(
                status = status ?: order.status,
                paymentStatus = paymentStatus ?: order.paymentStatus,
                cancelReason = cancelReason ?: order.cancelReason,
            )
        } else {
            order
        }
    }
    ui.adminDetailOrder?.let { detail ->
        if (detail.id == orderId) {
            ui.adminDetailOrder = detail.copy(
                status = status ?: detail.status,
                paymentStatus = paymentStatus ?: detail.paymentStatus,
                cancelReason = cancelReason ?: detail.cancelReason,
            )
        }
    }
    ui.invalidate()
}

internal fun MainActivity.markOrderPaid(orderId: String) {
    val self = this
    val previousOrders = ui.adminOrders
    val previousDetail = ui.adminDetailOrder
    patchAdminOrderLocal(orderId, paymentStatus = "paid")
    authScope.launch {
        try {
            OrderRepository.markOrderPaid(orderId)
            refreshAdminOrders()
            Toast.makeText(self, "Pembayaran ditandai lunas.", Toast.LENGTH_SHORT).show()
        } catch (error: Throwable) {
            ui.adminOrders = previousOrders
            ui.adminDetailOrder = previousDetail
            ui.invalidate()
            Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
        }
    }
}

/** Penjual menolak bukti bayar QRIS. */
internal fun MainActivity.rejectOrderPayment(orderId: String) {
    val self = this
    val previousOrders = ui.adminOrders
    val previousDetail = ui.adminDetailOrder
    patchAdminOrderLocal(orderId, paymentStatus = "rejected")
    authScope.launch {
        try {
            OrderRepository.rejectOrderPayment(orderId)
            refreshAdminOrders()
            Toast.makeText(self, "Bukti pembayaran ditolak.", Toast.LENGTH_SHORT).show()
        } catch (error: Throwable) {
            ui.adminOrders = previousOrders
            ui.adminDetailOrder = previousDetail
            ui.invalidate()
            Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
        }
    }
}

/** Muat pengaturan warung penjual (nama toko, nama asli, NIS, nomor telepon, ruangan, + QRIS) untuk layar profil. */
internal fun MainActivity.refreshSellerSettings() {
    authScope.launch {
        val stall = runCatching { OrderRepository.fetchMyStall() }.getOrNull()
        ui.myStallId = stall?.id
        ui.myStallName = stall?.name.orEmpty()
        ui.myStallRoom = stall?.location?.takeIf { it != "Kantin" }.orEmpty()
        ui.myStallQris = stall?.qrisImageUrl.orEmpty()
        val rawName = runCatching { SupabaseRepository.myRawDisplayName() }.getOrNull().orEmpty()
        val cleanName = SupabaseRepository.extractCleanName(rawName)
        val nis = SupabaseRepository.extractNis(rawName)
        val kelas = SupabaseRepository.extractClass(rawName)
        if (cleanName.isNotBlank()) ui.myDisplayName = cleanName
        if (nis.isNotBlank()) ui.myNis = nis
        if (kelas.isNotBlank()) ui.myClass = kelas
        val phone = runCatching { PhoneRepository.myPhone() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: stall?.sellerPhone.orEmpty()
        if (phone.isNotBlank()) ui.myPhone = phone
        if (stall != null && (ui.myDisplayName.isNotBlank() || ui.myPhone.isNotBlank())) {
            val ownerWithNis = if (ui.myNis.isNotBlank()) "${ui.myDisplayName} (NIS: ${ui.myNis})" else ui.myDisplayName
            OrderRepository.syncStallContactMetadata(stall.id, ownerWithNis, ui.myPhone)
        }
        ui.invalidate()
    }
}

/** Dialog pengaturan QRIS warung: unggah gambar atau tempel URL. */
internal fun MainActivity.showQrisSettings() {
    refreshSellerSettings()
    val current = ui.myStallQris
    val items = if (current.isBlank()) {
        arrayOf("Pilih gambar dari galeri", "Tempel URL gambar")
    } else {
        arrayOf("Lihat QR terpasang", "Pilih gambar dari galeri", "Tempel URL gambar", "Hapus QRIS")
    }
    android.app.AlertDialog.Builder(this).setTitle("QRIS ${ui.myStallName.takeIf { it.isNotBlank() } ?: "warung saya"}")
        .setItems(items) { _, which ->
            val choice = items[which]
            when {
                choice.startsWith("Lihat") -> showQrisPreview(current)
                choice.startsWith("Pilih") -> pickQrisImage()
                choice.startsWith("Tempel") -> showQrisUrlForm()
                choice.startsWith("Hapus") -> confirmRemoveQris()
            }
        }
        .show()
}

internal fun MainActivity.showQrisPreview(url: String) {
    val self = this
    authScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val bmp = runCatching {
            val conn = java.net.URL(url).openConnection().apply { connectTimeout = 8000; readTimeout = 8000 }
            conn.getInputStream().use { android.graphics.BitmapFactory.decodeStream(it) }
        }.getOrNull()
        launch(kotlinx.coroutines.Dispatchers.Main) {
            if (bmp != null) {
                ProductImageCache.images[url] = bmp
                val view = android.widget.ImageView(self).apply { setImageBitmap(bmp) }
                android.app.AlertDialog.Builder(self).setTitle("QRIS terpasang")
                    .setView(view).setPositiveButton("Tutup", null).show()
            } else {
                Toast.makeText(self, "QR tidak bisa dimuat.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** Konfirmasi sebelum menghapus QRIS warung (tindakan destruktif). */
internal fun MainActivity.confirmRemoveQris() {
    android.app.AlertDialog.Builder(this).setTitle("Hapus QRIS warung?")
        .setMessage("Pembeli tidak bisa bayar QRIS sampai QR baru dipasang.")
        .setPositiveButton("Hapus") { _, _ -> saveStallQrisUrl("") }
        .setNegativeButton("Batal", null)
        .show()
}

/** Penjual menyimpan URL gambar QRIS warungnya (paste URL). */
internal fun MainActivity.showQrisUrlForm() {
    val input = EditText(this).apply {
        hint = "https://..."
        setSingleLine(true)
        setText(ui.myStallQris)
    }
    android.app.AlertDialog.Builder(this).setTitle("URL gambar QRIS")
        .setView(input)
        .setPositiveButton("Simpan") { _, _ -> saveStallQrisUrl(input.text.toString().trim()) }
        .setNegativeButton("Batal", null)
        .show()
}

/** Penjual menyimpan URL gambar QRIS warungnya (paste URL). */
internal fun MainActivity.saveStallQrisUrl(url: String) {
    val self = this
    val stallId = ui.myStallId
    if (stallId == null) {
        Toast.makeText(this, "Warung belum ada.", Toast.LENGTH_SHORT).show()
        return
    }
    authScope.launch {
        try {
            OrderRepository.updateStallQris(stallId, url.ifBlank { null })
            ui.myStallQris = url
            ui.invalidate()
            Toast.makeText(self, "QRIS warung disimpan.", Toast.LENGTH_SHORT).show()
        } catch (error: Throwable) {
            Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
        }
    }
}

/** Penjual mengunggah file gambar QRIS ke Storage publik lalu menyimpan URL-nya. */
internal fun MainActivity.uploadStallQrisImage(uri: android.net.Uri) {
    val self = this
    val stallId = ui.myStallId
    if (stallId == null) {
        Toast.makeText(this, "Warung belum ada.", Toast.LENGTH_SHORT).show()
        return
    }
    authScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Gambar tidak bisa dibaca.")
            // QR tetap tajam di 1000px q90, jauh lebih ringan dari mentah.
            val bytes = compressForUpload(raw, maxEdge = 1000, quality = 90)
            require(bytes.size <= 5 * 1024 * 1024) { "Ukuran gambar maksimal 5MB." }
            val token = SupabaseRepository.currentAccessToken()
                ?: error("Sesi habis, masuk ulang.")
            val uid = SupabaseRepository.currentUid() ?: error("Sesi habis, masuk ulang.")
            val url = java.net.URL("${SupabaseRepository.baseUrl}/storage/v1/object/qris/$uid.jpg")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                setFixedLengthStreamingMode(bytes.size)
                setRequestProperty("apikey", SupabaseRepository.publishableKey)
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "image/jpeg")
                setRequestProperty("x-upsert", "true")
                connectTimeout = 15000
                readTimeout = 15000
            }
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) error("Unggah gagal (HTTP $code).")
            val publicUrl = "${SupabaseRepository.baseUrl}/storage/v1/object/public/qris/$uid.jpg"
            OrderRepository.updateStallQris(stallId, publicUrl)
            launch(kotlinx.coroutines.Dispatchers.Main) {
                ui.myStallQris = publicUrl
                ui.invalidate()
                Toast.makeText(self, "QRIS warung disimpan.", Toast.LENGTH_SHORT).show()
            }
        } catch (error: Throwable) {
            launch(kotlinx.coroutines.Dispatchers.Main) {
                Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun MainActivity.pickQrisImage() {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
    }
    startActivityForResult(intent, REQUEST_QRIS_IMAGE)
}

internal fun MainActivity.startAdminOrders() {
    adminObserver?.close()
    adminObserver = OrderRepository.observeOrders(authScope, "admin-orders") { action ->
        if (!applyRealtimeOrderToSeller(action)) refreshAdminOrders()
    }
    refreshAdminOrders()
}

private fun MainActivity.applyRealtimeOrderToSeller(action: PostgresAction): Boolean {
    return when (action) {
        is PostgresAction.Update -> {
            val patch = OrderRepository.decodeOrderPatch(action.record) ?: return false
            if (ui.adminOrders.none { it.id == patch.id }) return false
            patchAdminOrderLocal(
                patch.id,
                status = patch.status,
                paymentStatus = patch.paymentStatus,
                cancelReason = patch.paymentReference,
            )
            detectAndNotifySellerOrderChanges(ui.adminOrders)
            true
        }
        is PostgresAction.Delete -> {
            val deletedId = OrderRepository.extractRecordId(action.oldRecord) ?: return false
            if (ui.adminOrders.any { it.id == deletedId }) {
                ui.adminOrders = ui.adminOrders.filterNot { it.id == deletedId }
                if (ui.adminDetailOrder?.id == deletedId) ui.adminDetailOrder = null
                ui.invalidate()
            }
            true
        }
        else -> false
    }
}

/** Pilihan peran dari layar ROLE: hanya Pembeli/Penjual. Developer masuk
 * otomatis lewat login karena role-nya dikunci di database. */
internal fun MainActivity.backToSellerHome() {
    authScope.launch {
        if (!SupabaseRepository.hasSession()) {
            ui.myRole = "buyer"
            ui.showHome()
            refreshProducts()
            return@launch
        }
        val prof = SupabaseRepository.fetchMyAccountProfile()
        val role = prof?.role ?: SupabaseRepository.currentRole()
        if (role == "developer") {
            if (!developerUnlocked) {
                requirePrivilegedAccess("developer") { backToSellerHome() }
                return@launch
            }
            ui.myRole = "developer"
            ui.showDev()
            refreshDeveloper()
            return@launch
        }
        val isVerifiedSeller = prof?.isSellerVerified == true || role == "seller" || ui.myIsSellerVerified
        ui.myIsSellerVerified = isVerifiedSeller
        if (!isVerifiedSeller) {
            ui.myRole = "buyer"
            ui.showHome()
            refreshProducts()
            return@launch
        }
        ui.myRole = "seller"
        ensureSellerStallName {
            ui.showAdmin()
            refreshAdminOrders()
        }
    }
}

// ============ Nama toko/warung wajib untuk penjual ============

internal fun MainActivity.isDefaultStallName(name: String): Boolean {
    val t = name.trim()
    return t.isEmpty() ||
        t.equals("Kantin Penjual", ignoreCase = true) ||
        t.equals("Kantin Jajanin", ignoreCase = true)
}

/**
 * Pastikan penjual punya nama toko/brand sebelum dashboard dibuka.
 * Bila warung belum ada / nama masih default, tampilkan dialog wajib
 * isi; bila sudah ada nama proper, langsung [onReady].
 */
/**
 * Pastikan penjual punya:
 * 1) Nama asli pemilik akun + NIS wajib (profiles.display_name)
 * 2) Nama toko/warung (stalls.name)
 * 3) Nomor telepon aktif (wajib bagi penjual)
 * sebelum dashboard dibuka.
 */
internal fun MainActivity.ensureSellerStallName(onReady: () -> Unit) {
    authScope.launch {
        val rawName = runCatching { SupabaseRepository.myRawDisplayName() }.getOrNull()?.trim().orEmpty()
        val cleanName = SupabaseRepository.extractCleanName(rawName)
        val nis = SupabaseRepository.extractNis(rawName)
        val kelas = SupabaseRepository.extractClass(rawName)
        if (cleanName.isNotBlank()) ui.myDisplayName = cleanName
        if (nis.isNotBlank()) ui.myNis = nis
        if (kelas.isNotBlank()) ui.myClass = kelas

        val stall = runCatching { OrderRepository.fetchMyStall() }.getOrNull()
        val current = stall?.name?.trim().orEmpty()
        if (stall == null || isDefaultStallName(current)) {
            showStallNameDialog(prefill = if (isDefaultStallName(current)) "" else current) {
                ensureSellerStallName(onReady)
            }
            return@launch
        }

        ui.myStallId = stall.id
        ui.myStallName = current
        ui.myStallRoom = stall.location.takeIf { it != "Kantin" }.orEmpty()
        ui.myStallQris = stall.qrisImageUrl.orEmpty()

        val phone = runCatching { PhoneRepository.myPhone() }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: stall.sellerPhone
        if (phone.isNotBlank()) {
            ui.myPhone = phone
            val ownerWithNis = if (ui.myNis.isNotBlank()) "${ui.myDisplayName} (NIS: ${ui.myNis})" else ui.myDisplayName
            OrderRepository.syncStallContactMetadata(stall.id, ownerWithNis, phone)
            ui.invalidate()
            onReady()
            return@launch
        }

        showSellerMandatoryPhoneDialog(stall.id) { onReady() }
    }
}

/**
 * Dialog wajib isi nomor telepon penjual; tidak bisa dilewat tanpa disimpan.
 */
internal fun MainActivity.showSellerMandatoryPhoneDialog(stallId: String, onSaved: () -> Unit) {
    val self = this
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 24, 48, 0)
    }
    val input = EditText(this).apply {
        hint = "cth. 0812xxxxxxx"
        inputType = android.text.InputType.TYPE_CLASS_PHONE
        setSingleLine(true)
        setText(ui.myPhone)
    }
    container.addView(input)
    val dialog = android.app.AlertDialog.Builder(this)
        .setTitle("Nomor Telepon Penjual (Wajib)")
        .setMessage("Penjual wajib mencantumkan nomor telepon/WA aktif agar tampil di detail pesanan pembeli untuk koordinasi pengambilan.")
        .setView(container)
        .setPositiveButton("Simpan", null)
        .setNegativeButton("Batal") { _, _ ->
            ui.myRole = "buyer"
            ui.showHome()
            refreshProducts()
        }
        .show()
    dialog.setCancelable(false)
    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val raw = input.text.toString().trim()
        val norm = PhoneRepository.normalizePhone(raw)
        if (norm == null) {
            Toast.makeText(this, "Nomor telepon tidak valid. Gunakan 08... atau +62...", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        val saveBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        saveBtn.isEnabled = false
        authScope.launch {
            try {
                val savedPhone = runCatching { PhoneRepository.setMyPhone(raw) }.getOrDefault(norm)
                ui.myPhone = savedPhone
                OrderRepository.syncStallContactMetadata(stallId, ui.myDisplayName, savedPhone)
                ui.invalidate()
                dialog.dismiss()
                Toast.makeText(self, "Nomor telepon penjual disimpan.", Toast.LENGTH_SHORT).show()
                onSaved()
            } catch (error: Throwable) {
                saveBtn.isEnabled = true
                Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            }
        }
    }
    input.requestFocus()
}

/** Dialog ubah ruangan pengambilan default warung dari halaman Profil seller. */
internal fun MainActivity.showStallRoomEdit() {
    val self = this
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 24, 48, 0)
    }
    val input = EditText(this).apply {
        hint = "cth. Ruang XII RPL 1 / Kantin Utama"
        setSingleLine(true)
        setText(ui.myStallRoom)
        selectAll()
    }
    container.addView(input)
    val dialog = android.app.AlertDialog.Builder(this).setTitle("Ambil di Ruangan")
        .setMessage("Ruangan/kelas tempat pembeli mengambil pesanan warungmu.")
        .setView(container)
        .setPositiveButton("Simpan", null)
        .setNegativeButton("Batal", null)
        .show()
    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val value = input.text.toString().trim()
        if (value.isBlank()) {
            Toast.makeText(this, "Nama ruangan tidak boleh kosong.", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        val saveBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        saveBtn.isEnabled = false
        authScope.launch {
            try {
                val stallId = ui.myStallId ?: OrderRepository.sellerStallId()
                if (stallId != null) {
                    OrderRepository.updateStallLocation(stallId, value)
                }
                ui.myStallRoom = value
                recordRecentSellerRoom(value)
                ui.invalidate()
                dialog.dismiss()
                Toast.makeText(self, "Ruangan ambil disimpan: $value", Toast.LENGTH_SHORT).show()
            } catch (error: Throwable) {
                saveBtn.isEnabled = true
                Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            }
        }
    }
    input.requestFocus()
}

internal fun MainActivity.copyPhoneToClipboard(phone: String) {
    val clean = if (phone.startsWith("+62")) "0" + phone.removePrefix("+62") else phone
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    cm?.setPrimaryClip(android.content.ClipData.newPlainText("Nomor Telepon Penjual", clean))
    Toast.makeText(this, "Nomor disalin: $clean", Toast.LENGTH_SHORT).show()
}

internal fun MainActivity.openDialer(phone: String) {
    runCatching {
        val clean = phone.filter { it.isDigit() || it == '+' }
        startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$clean")))
    }.onFailure {
        Toast.makeText(this, "Tidak dapat membuka aplikasi telepon.", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Dialog wajib isi nama toko; tidak bisa dilewat tanpa disimpan.
 * Tombol Batal = mundur ke home pembeli (tidak masuk dashboard).
 */
internal fun MainActivity.showStallNameDialog(prefill: String, onSaved: () -> Unit) {
    val self = this
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 24, 48, 0)
    }
    val input = EditText(this).apply {
        hint = "cth. Warung Bu Tini"
        setSingleLine(true)
        setText(prefill)
    }
    container.addView(input)
    val dialog = android.app.AlertDialog.Builder(this).setTitle("Nama toko/warung")
        .setMessage("Setiap penjual wajib punya nama toko/brand. Isi dulu sebelum membuka dashboard.")
        .setView(container)
        .setPositiveButton("Simpan", null)
        .setNegativeButton("Batal") { _, _ ->
            ui.myRole = "buyer"
            ui.showHome()
            refreshProducts()
        }
        .show()
    dialog.setCancelable(false)
    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val value = input.text.toString().trim()
        if (value.length < 3) {
            Toast.makeText(this, "Nama toko minimal 3 karakter.", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        val saveBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        saveBtn.isEnabled = false
        authScope.launch {
            try {
                val stallId = OrderRepository.ensureSellerStall(value)
                ui.myStallId = stallId
                ui.myStallName = value
                ui.invalidate()
                dialog.dismiss()
                Toast.makeText(self, "Warung \"$value\" siap digunakan.", Toast.LENGTH_SHORT).show()
                onSaved()
            } catch (error: Throwable) {
                saveBtn.isEnabled = true
                Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            }
        }
    }
    input.requestFocus()
    input.post {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }
}

/**
 * Dialog ubah nama toko dari layar Profil seller (bisa dibatalkan).
 * Memakai nama toko yang tersimpan, bukan email akun.
 */
internal fun MainActivity.showStallNameEdit() {
    val self = this
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 24, 48, 0)
    }
    val input = EditText(this).apply {
        hint = "cth. Warung Bu Tini"
        setSingleLine(true)
        setText(ui.myStallName)
        selectAll()
    }
    container.addView(input)
    val dialog = android.app.AlertDialog.Builder(this).setTitle("Ubah nama toko")
        .setMessage("Nama ini tampil di profil dan dashboard penjual.")
        .setView(container)
        .setPositiveButton("Simpan", null)
        .setNegativeButton("Batal", null)
        .show()
    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val value = input.text.toString().trim()
        if (value.length < 3) {
            Toast.makeText(this, "Nama toko minimal 3 karakter.", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        if (value == ui.myStallName.trim()) {
            dialog.dismiss()
            return@setOnClickListener
        }
        val saveBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        saveBtn.isEnabled = false
        authScope.launch {
            try {
                val stallId = ui.myStallId
                val newId = if (stallId != null) {
                    OrderRepository.updateStallName(stallId, value)
                    stallId
                } else {
                    OrderRepository.ensureSellerStall(value)
                }
                ui.myStallId = newId
                ui.myStallName = value
                ui.invalidate()
                dialog.dismiss()
                Toast.makeText(self, "Nama toko diganti jadi \"$value\".", Toast.LENGTH_SHORT).show()
            } catch (error: Throwable) {
                saveBtn.isEnabled = true
                Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            }
        }
    }
    input.requestFocus()
}

internal fun MainActivity.enterSellerDashboard() {
    // Dual-mode v2.0: tanpa kode akses developer; akun terverifikasi OTP Email langsung masuk Mode Penjual.
    if (!SupabaseRepository.hasSession()) {
        Toast.makeText(this, "Masuk dulu untuk membuka Mode Penjual.", Toast.LENGTH_LONG).show()
        showAuthForm(false)
        return
    }
    authScope.launch {
        val prof = SupabaseRepository.fetchMyAccountProfile()
        val isVerified = prof?.isSellerVerified == true || prof?.role == "seller" || ui.myIsSellerVerified
        ui.myIsSellerVerified = isVerified
        prof?.stallId?.takeIf { it.isNotBlank() }?.let { ui.myStallId = it }
        prof?.stallName?.takeIf { it.isNotBlank() }?.let { ui.myStallName = it }
        if (!isVerified) {
            ui.myRole = "buyer"
            showSellerOtpActivationDialog()
            return@launch
        }
        ui.myRole = "seller"
        ui.adminOrders = emptyList()
        ui.adminLoading = false
        ui.adminError = null
        ui.adminNotifOpen = false
        ensureSellerStallName {
            ui.showAdmin()
            startAdminOrders()
            refreshSellerProducts()
            registerMyDevice()
            refreshInbox()
            startInboxObserver()
            startChatObserver()
            ensureOnlinePresence("seller")
            pendingChatConversationId?.let { cid ->
                pendingChatConversationId = null
                openConversationById(cid)
            }
        }
    }
}

internal fun MainActivity.refreshAdminOrders() {
    if (!SupabaseRepository.hasSession()) return
    adminRefreshJob?.cancel()
    ui.adminLoading = ui.adminOrders.isEmpty()
    ui.adminError = null
    ui.invalidate()
    adminRefreshJob = authScope.launch {
        try {
            val freshOrders = OrderRepository.fetchAdminTodayOrders()
            detectAndNotifySellerOrderChanges(freshOrders)
            ui.adminOrders = freshOrders
            ui.adminError = null
            // Jika layar detail sedang terbuka, ikutkan perubahan realtime
            // (mis. pembeli baru mengirim bukti bayar) tanpa perlu tarik-ulang.
            ui.adminDetailId?.let { id ->
                ui.adminOrders.firstOrNull { it.id == id }?.let { fresh ->
                    // Nomor HP hanya diambil saat detail dibuka; pertahankan saat realtime refresh.
                    ui.adminDetailOrder = fresh.copy(buyerPhone = ui.adminDetailOrder?.buyerPhone.orEmpty())
                }
            }
        } catch (error: Throwable) {
            ui.adminError = SupabaseRepository.errorMessage(error)
        } finally {
            ui.adminLoading = false
            ui.invalidate()
        }
    }
}

/** Auto-refresh penjual setiap 1 detik di latar belakang tanpa mengaktifkan spinner loading. */
internal suspend fun MainActivity.silentRefreshSellerTick() {
    if (!SupabaseRepository.hasSession() || adminStatusBusy) return
    runCatching {
        val freshOrders = OrderRepository.fetchAdminTodayOrders()
        detectAndNotifySellerOrderChanges(freshOrders)
        if (freshOrders != ui.adminOrders) {
            ui.adminOrders = freshOrders
            ui.adminDetailId?.let { id ->
                freshOrders.firstOrNull { it.id == id }?.let { fresh ->
                    ui.adminDetailOrder = fresh.copy(buyerPhone = ui.adminDetailOrder?.buyerPhone.orEmpty())
                }
            }
            ui.adminLoading = false
            ui.invalidate()
        }
    }
}

// ================= Developer Dashboard =================

internal fun MainActivity.cancelAdminOrder(orderId: String) {
    val self = this
    val order = ui.adminOrders.firstOrNull { it.id == orderId }
        ?: ui.adminDetailOrder?.takeIf { it.id == orderId }
        ?: return
    if (isTerminalStatus(order.status)) return

    val input = EditText(this).apply {
        hint = "cth. Stok menu habis / Warung tutup"
        setSingleLine(false)
        maxLines = 3
    }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 20, 48, 8)
        addView(input)
    }

    val dialog = android.app.AlertDialog.Builder(this)
        .setTitle("Batalkan pesanan #${order.pickupCode}?")
        .setMessage("${order.buyerName}\nMasukkan alasan pembatalan (wajib diisi): Jatah pesanan harian pembeli & stok menu akan dikembalikan otomatis.")
        .setView(container)
        .setPositiveButton("Batalkan Pesanan", null)
        .setNegativeButton("Kembali", null)
        .create()

    dialog.setOnShowListener {
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val reason = input.text?.toString()?.trim().orEmpty()
            if (reason.isBlank()) {
                input.error = "Alasan pembatalan wajib diisi"
                Toast.makeText(self, "Wajib mengisi alasan pembatalan pesanan.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            submitAdminOrderCancel(orderId, reason)
        }
    }
    dialog.show()
    input.requestFocus()
}

internal fun MainActivity.submitAdminOrderCancel(orderId: String, reason: String) {
    val self = this
    if (adminStatusBusy) return
    adminStatusBusy = true
    val previousOrders = ui.adminOrders
    val previousDetail = ui.adminDetailOrder
    patchAdminOrderLocal(orderId, status = STATUS_CANCELLED, cancelReason = reason)
    authScope.launch {
        try {
            OrderRepository.cancelSellerOrder(orderId, reason)
            refreshAdminOrders()
            refreshSellerProducts()
            Toast.makeText(self, "Pesanan dibatalkan & kuota pembeli dikembalikan.", Toast.LENGTH_SHORT).show()
        } catch (error: Throwable) {
            ui.adminOrders = previousOrders
            ui.adminDetailOrder = previousDetail
            ui.invalidate()
            Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
        } finally {
            adminStatusBusy = false
        }
    }
}

internal fun MainActivity.submitAdminOrderStatus(orderId: String, nextStatus: String) {
    val self = this
    if (adminStatusBusy) return
    adminStatusBusy = true
    val previousOrders = ui.adminOrders
    val previousDetail = ui.adminDetailOrder
    patchAdminOrderLocal(orderId, status = nextStatus)
    authScope.launch {
        try {
            OrderRepository.updateOrderStatus(orderId, nextStatus)
            refreshAdminOrders()
        } catch (error: Throwable) {
            ui.adminOrders = previousOrders
            ui.adminDetailOrder = previousDetail
            ui.invalidate()
            Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
        } finally {
            adminStatusBusy = false
        }
    }
}

/** Memuat satu order ke layar detail (dipanggil dari tap baris & deep-link notifikasi). */
internal fun MainActivity.loadAdminOrderDetail(orderId: String) {
    val cached = ui.adminOrders.firstOrNull { it.id == orderId }
    ui.adminDetailId = orderId
    if (cached != null) ui.adminDetailOrder = cached
    ui.adminDetailLoading = cached == null && ui.adminDetailOrder?.id != orderId
    ui.adminDetailError = null
    ui.invalidate()
    authScope.launch {
        try {
            val order = OrderRepository.fetchOrderById(orderId)
            if (order == null) {
                ui.adminDetailOrder = null
                ui.adminDetailError = "Pesanan tidak ditemukan atau bukan milikmu."
            } else {
                ui.adminDetailOrder = order
                ui.adminDetailError = null
            }
        } catch (error: Throwable) {
            if (ui.adminDetailOrder == null) {
                ui.adminDetailError = SupabaseRepository.errorMessage(error)
            }
        } finally {
            ui.adminDetailLoading = false
            ui.invalidate()
        }
    }
}

/** Mengubah status order dari layar detail; UI sudah diupdate optimis oleh view. */
internal fun MainActivity.setAdminOrderStatus(orderId: String, status: String) {
    val self = this
    if (adminStatusBusy) return
    adminStatusBusy = true
    authScope.launch {
        try {
            OrderRepository.updateOrderStatus(orderId, status)
            refreshAdminOrders()
        } catch (error: Throwable) {
            Toast.makeText(self, SupabaseRepository.errorMessage(error), Toast.LENGTH_LONG).show()
            // Tarik ulang daftar juga: update optimis di kartu dashboard harus dibatalkan.
            refreshAdminOrders()
            loadAdminOrderDetail(orderId)
        } finally {
            adminStatusBusy = false
        }
    }
}

/** Order dari tap notifikasi; dikonsumsi routeSignedInUser bila restore session menang lebih dulu. */
internal fun MainActivity.openOrderDetail(orderId: String) {
    if (ui.currentScreen != Screen.ADMIN_DETAIL) ui.go(Screen.ADMIN_DETAIL)
    loadAdminOrderDetail(orderId)
}

/** Tap notifikasi "Pesanan Baru": buka dashboard + layar detail order terkait. */
