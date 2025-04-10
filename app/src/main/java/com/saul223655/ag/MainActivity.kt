package com.saul223655.ag

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.*
import androidx.activity.result.contract.ActivityResultContracts.TakePicturePreview
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.saul223655.ag.ui.theme.AGTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.*
import java.net.URLConnection

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AGTheme {
                SignatureClassifier()
            }
        }
    }
}

@Composable
fun SignatureClassifier() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var imageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var classificationResult by remember { mutableStateOf<JSONObject?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pickImageLauncher = rememberLauncherForActivityResult(GetContent()) { uri: Uri? ->
        uri?.let {
            val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, it)
            imageBitmap = bitmap
            classificationResult = null
            error = null
        }
    }

    val takePhotoLauncher = rememberLauncherForActivityResult(TakePicturePreview()) { bitmap ->
        bitmap?.let {
            imageBitmap = it
            classificationResult = null
            error = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🖋️ RECONFIRMANE", style = MaterialTheme.typography.headlineLarge)
        Text("Sistema inteligente de reconocimiento de firmas", fontWeight = FontWeight.Medium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pickImageLauncher.launch("image/*") }) {
                Text("Subir Imagen")
            }
            Button(onClick = { takePhotoLauncher.launch(null) }) {
                Text("Tomar Foto")
            }
            if (imageBitmap != null) {
                OutlinedButton(onClick = {
                    imageBitmap = null
                    classificationResult = null
                    error = null
                }) {
                    Text("Limpiar")
                }
            }
        }

        imageBitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(8.dp)
            )

            Button(
                onClick = {
                    coroutineScope.launch {
                        isLoading = true
                        error = null

                        try {
                            val byteArray = bitmapToByteArray(it)
                            classifyImage(context, byteArray,
                                onResult = { response ->
                                    classificationResult = JSONObject(response)
                                },
                                onError = { errMsg ->
                                    error = errMsg
                                }
                            )
                        } catch (e: Exception) {
                            error = "Error al procesar imagen: ${e.message}"
                        }

                        isLoading = false
                    }
                },
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Analizando...")
                } else {
                    Text("Clasificar Firma")
                }
            }
        }

        error?.let {
            Text("❌ $it", color = MaterialTheme.colorScheme.error)
        }

        classificationResult?.let { result ->
            val predicted = result.getJSONObject("predicted_class")
            val label = predicted.getString("class")
            val prob = predicted.getDouble("probability")
            val (confText, icon) = when {
                prob >= 0.86 -> "Confianza Alta" to "✅"
                prob >= 0.51 -> "Confianza Media" to "⚠️"
                else -> "Confianza Baja" to "❌"
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Resultado del Análisis:", style = MaterialTheme.typography.titleLarge)
                    Text("$icon Clasificación: $label", style = MaterialTheme.typography.titleMedium)
                    Text("Nivel de confianza: $confText (${(prob * 100).toInt()}%)")

                    Divider()

                    val details = result.getJSONArray("probabilities")
                    Text("Análisis Detallado:", fontWeight = FontWeight.Bold)
                    for (i in 0 until details.length()) {
                        val obj = details.getJSONObject(i)
                        val cls = obj.getString("class")
                        val prb = obj.getDouble("probability") * 100
                        Text("• $cls: ${"%.2f".format(prb)}%")
                    }
                }
            }
        }

        if (imageBitmap == null && classificationResult == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📄 Sin imagen seleccionada", fontWeight = FontWeight.SemiBold)
                Text("Suba una imagen o tome una foto para comenzar.")
            }
        }
    }
}

fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
    return stream.toByteArray()
}


// Función para clasificar usando API
fun classifyImage(context: Context, imageBytes: ByteArray, onResult: (String) -> Unit, onError: (String) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val client = OkHttpClient()
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", "signature.jpg",
                    RequestBody.create("image/jpeg".toMediaTypeOrNull(), imageBytes)
                )
                .build()

            val request = Request.Builder()
                .url("http://54.173.209.117/classify-signature/")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                withContext(Dispatchers.Main) {
                    onResult(responseBody)
                }
            } else {
                withContext(Dispatchers.Main) {
                    onError("Error en la respuesta del servidor")
                }
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onError("Excepción: ${e.localizedMessage}")
            }
        }
    }
}
