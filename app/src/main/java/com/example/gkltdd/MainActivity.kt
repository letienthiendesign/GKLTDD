package com.example.gkltdd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AppRoot() } }
    }
}

private val auth get() = FirebaseAuth.getInstance()
private val db get() = FirebaseFirestore.getInstance()

/* ---------------- Gốc: kiểm tra đăng nhập + lấy quyền ---------------- */
@Composable
fun AppRoot() {
    var user by remember { mutableStateOf<FirebaseUser?>(auth.currentUser) }
    var role by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(user?.uid) {
        val u = user
        if (u == null) {
            role = null
        } else {
            db.collection("users").document(u.uid).get()
                .addOnSuccessListener { role = it.getString("role") ?: "user" }
                .addOnFailureListener { role = "user" }
        }
    }

    Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F7F9)) {
        when {
            user == null -> LoginScreen { user = auth.currentUser }
            role == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            else -> ProductScreen(
                isAdmin = role == "admin",
                email = user?.email ?: "",
                onLogout = { auth.signOut(); user = null; role = null }
            )
        }
    }
}

/* ---------------- Đăng nhập / Đăng ký ---------------- */
@Composable
fun LoginScreen(onSuccess: () -> Unit) {
    val ctx = LocalContext.current
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("ECOMER", fontSize = 32.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(email, { email = it }, label = { Text("Email") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(pass, { pass = it }, label = { Text("Mật khẩu") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation())
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                if (email.isBlank() || pass.isBlank()) { toast("Nhập email và mật khẩu"); return@Button }
                loading = true
                auth.signInWithEmailAndPassword(email.trim(), pass)
                    .addOnSuccessListener { loading = false; onSuccess() }
                    .addOnFailureListener { loading = false; toast("Đăng nhập thất bại: ${it.message}") }
            },
            enabled = !loading, modifier = Modifier.fillMaxWidth()
        ) { Text("ĐĂNG NHẬP") }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                if (email.isBlank() || pass.length < 6) { toast("Email hợp lệ và mật khẩu từ 6 ký tự"); return@OutlinedButton }
                loading = true
                auth.createUserWithEmailAndPassword(email.trim(), pass)
                    .addOnSuccessListener { res ->
                        val uid = res.user!!.uid
                        db.collection("users").document(uid)
                            .set(mapOf("email" to email.trim(), "role" to "user"))
                            .addOnCompleteListener { loading = false; onSuccess() }
                    }
                    .addOnFailureListener { loading = false; toast("Đăng ký thất bại: ${it.message}") }
            },
            enabled = !loading, modifier = Modifier.fillMaxWidth()
        ) { Text("ĐĂNG KÝ") }
    }
}

/* ---------------- Màn hình sản phẩm ---------------- */
@Composable
fun ProductScreen(isAdmin: Boolean, email: String, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    var products by remember { mutableStateOf(listOf<Product>()) }

    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var imageB64 by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Product?>(null) }

    // Lắng nghe realtime danh sách sản phẩm
    DisposableEffect(Unit) {
        val reg = db.collection("products").addSnapshotListener { snap, _ ->
            products = snap?.documents?.map {
                Product(
                    id = it.id,
                    name = it.getString("name") ?: "",
                    type = it.getString("type") ?: "",
                    price = it.getLong("price") ?: 0,
                    image = it.getString("image") ?: ""
                )
            } ?: emptyList()
        }
        onDispose { reg.remove() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) imageB64 = uriToBase64(ctx, uri)
    }

    fun clearForm() {
        name = ""; type = ""; price = ""; imageB64 = ""; editingId = null
    }

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()

    fun save() {
        val p = price.toLongOrNull()
        if (name.isBlank() || type.isBlank() || p == null) { toast("Nhập đủ tên, loại, giá hợp lệ"); return }
        val data = mapOf("name" to name.trim(), "type" to type.trim(), "price" to p, "image" to imageB64)
        val id = editingId
        val task = if (id == null) db.collection("products").add(data)
        else db.collection("products").document(id).update(data)
        task.addOnSuccessListener { toast(if (id == null) "Đã thêm" else "Đã cập nhật"); clearForm() }
            .addOnFailureListener { toast("Lỗi: ${it.message}") }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dữ liệu sản phẩm", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("$email (${if (isAdmin) "admin" else "user"})", fontSize = 12.sp, color = Color.Gray)
            }
            TextButton(onClick = onLogout) { Text("Đăng xuất") }
        }
        Spacer(Modifier.height(8.dp))

        if (isAdmin) {
            OutlinedTextField(name, { name = it }, label = { Text("Tên sản phẩm") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(type, { type = it }, label = { Text("Loại sản phẩm") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(price, { price = it.filter(Char::isDigit) }, label = { Text("Giá") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picker.launch("image/*") }) { Text("Chọn hình ảnh") }
                Spacer(Modifier.width(12.dp))
                if (imageB64.isNotEmpty()) Base64Image(imageB64, Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)))
                else Text("Chưa chọn ảnh", color = Color.Gray)
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = { save() }, modifier = Modifier.weight(1f)) {
                    Text(if (editingId == null) "THÊM SẢN PHẨM" else "CẬP NHẬT")
                }
                if (editingId != null) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { clearForm() }) { Text("Hủy") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Text("Danh sách sản phẩm:", fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(products, key = { it.id }) { p ->
                ProductItem(
                    p, isAdmin,
                    onEdit = {
                        editingId = p.id; name = p.name; type = p.type
                        price = p.price.toString(); imageB64 = p.image
                    },
                    onDelete = { deleting = p }
                )
            }
        }
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Xóa sản phẩm") },
            text = { Text("Bạn có chắc muốn xóa \"${p.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    db.collection("products").document(p.id).delete()
                        .addOnSuccessListener { toast("Đã xóa") }
                        .addOnFailureListener { toast("Lỗi: ${it.message}") }
                    if (editingId == p.id) clearForm()
                    deleting = null
                }) { Text("Xóa", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Hủy") } }
        )
    }
}

@Composable
fun ProductItem(p: Product, isAdmin: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFF42A5F5)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Base64Image(p.image, Modifier.size(72.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Tên sp: ${p.name}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Giá sp: ${p.price}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Loại sp: ${p.type}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            if (isAdmin) {
                Column {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, "Sửa", tint = Color(0xFFFFB300))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, "Xóa", tint = Color.Red)
                    }
                }
            }
        }
    }
}

/* ---------------- Xử lý ảnh ---------------- */
@Composable
fun Base64Image(b64: String, modifier: Modifier) {
    val bmp = remember(b64) {
        runCatching {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) Image(bmp, null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Color.LightGray))
}

// Thu nhỏ ảnh (tối đa 400px) + nén JPEG 70% để nằm gọn trong giới hạn 1MB/document
fun uriToBase64(context: Context, uri: Uri): String {
    val original = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        ?: return ""
    val ratio = minOf(400f / original.width, 400f / original.height, 1f)
    val scaled = Bitmap.createScaledBitmap(
        original, (original.width * ratio).toInt(), (original.height * ratio).toInt(), true
    )
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}