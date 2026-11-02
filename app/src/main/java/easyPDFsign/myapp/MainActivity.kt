package easyPDFsign.myapp

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Ink = Color(0xFF173B35)
private val Mint = Color(0xFFDAEEE7)
private val Paper = Color(0xFFF7F8F5)
private val Muted = Color(0xFF63716D)
private typealias Stroke = List<Offset>

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EasyPdfSignApp() }
    }
}

@Composable
private fun EasyPdfSignApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pdfFile by remember { mutableStateOf<File?>(null) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var strokes by remember { mutableStateOf<List<Stroke>>(emptyList()) }
    var signatureReady by remember { mutableStateOf(false) }
    var signatureCenter by remember { mutableStateOf(Offset(.5f, .72f)) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var signing by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val copied = withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, "opened_${System.currentTimeMillis()}.pdf")
                    context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                        ?: error("The selected file could not be opened.")
                    file
                }
                val pages = withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.open(copied, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
                }
                pdfFile = copied
                sourceUri = uri
                pageCount = pages
                pageIndex = 0
                pageBitmap = null
                strokes = emptyList()
                signatureReady = false
                signatureCenter = Offset(.5f, .72f)
                zoom = 1f
                pan = Offset.Zero
            } catch (_: Exception) { message = "Could not open the PDF. Please check that the file is valid and try again." }
            finally { busy = false }
        }
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null && sourceUri != null && sameDocument(uri, sourceUri!!)) {
            message = "Choose a different name or location to keep the original file unchanged."
        } else if (uri != null) {
            val source = pdfFile
            if (source != null && signatureReady) scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) {
                        val signatureBitmap = signatureBitmap(strokes)
                        try {
                          PDFBoxResourceLoader.init(context)
                          PDDocument.load(source).use { document ->
                            val page = document.getPage(pageIndex)
                            val box = page.cropBox
                            val width = box.width
                            val height = box.height
                            val signWidth = width * .38f
                            val signHeight = height * .14f
                            val x = box.lowerLeftX + (signatureCenter.x * width - signWidth / 2).coerceIn(0f, width - signWidth)
                            val yTop = signatureCenter.y * height
                            val y = box.lowerLeftY + (height - yTop - signHeight / 2).coerceIn(0f, height - signHeight)
                            val image = LosslessFactory.createFromImage(document, signatureBitmap)
                            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                                stream.drawImage(image, x, y, signWidth, signHeight)
                            }
                            context.contentResolver.openOutputStream(uri)?.use { document.save(it) }
                                ?: error("The destination could not be opened for saving.")
                          }
                        } finally { signatureBitmap.recycle() }
                    }
                    message = "Signed PDF saved successfully."
                } catch (_: Exception) { message = "Could not save the PDF. Please try another destination." }
                finally { busy = false }
            }
        }
    }

    LaunchedEffect(pdfFile, pageIndex) {
        val file = pdfFile ?: return@LaunchedEffect
        pageBitmap = withContext(Dispatchers.IO) {
            try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        renderer.openPage(pageIndex).use { page ->
                            val scale = (1200f / page.width).coerceAtMost(3f)
                            createBitmap((page.width * scale).toInt(), (page.height * scale).toInt()).also {
                                it.eraseColor(android.graphics.Color.WHITE)
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            } catch (_: Exception) { null }
        }
    }
    DisposableEffect(pageBitmap) {
        val bitmapToRelease = pageBitmap
        onDispose { bitmapToRelease?.recycle() }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Ink, secondary = Color(0xFF67A792), background = Paper)) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
                when {
                    pdfFile == null -> HomeScreen(
                        busy, onPick = { openPdf.launch(arrayOf("application/pdf")) },
                        onHelp = { help = true }, message = message, onDismiss = { message = null }
                    )
                    else -> EditorScreen(
                        fileName = pdfFile!!.name, bitmap = pageBitmap, pageIndex = pageIndex, pageCount = pageCount,
                        signatureReady = signatureReady, strokes = strokes, center = signatureCenter, busy = busy,
                        onPrevious = { if (pageIndex > 0) { pageIndex--; zoom = 1f; pan = Offset.Zero } },
                        onNext = { if (pageIndex < pageCount - 1) { pageIndex++; zoom = 1f; pan = Offset.Zero } },
                        onBack = { pdfFile = null; pageBitmap = null },
                        onSign = { signing = true },
                        onSave = { savePdf.launch("signed_${System.currentTimeMillis()}.pdf") },
                        onDragSignature = { signatureCenter = it }, zoom = zoom, pan = pan,
                        onZoom = { zoom = it; pan = Offset.Zero },
                        onTransform = { zoomChange, panChange, centroid, viewport ->
                            val nextZoom = (zoom * zoomChange).coerceIn(1f, 5f)
                            val relative = Offset(centroid.x - viewport.width / 2f, centroid.y - viewport.height / 2f)
                            val nextPan = Offset(
                                relative.x - (relative.x - pan.x) * (nextZoom / zoom) + panChange.x,
                                relative.y - (relative.y - pan.y) * (nextZoom / zoom) + panChange.y
                            )
                            val pageRatio = (pageBitmap?.width?.toFloat() ?: 1f) / (pageBitmap?.height?.toFloat() ?: 1f)
                            val viewRatio = viewport.width / viewport.height
                            val fittedWidth = if (viewRatio > pageRatio) viewport.height * pageRatio else viewport.width
                            val fittedHeight = if (viewRatio > pageRatio) viewport.height else viewport.width / pageRatio
                            val maxX = fittedWidth * (nextZoom - 1f) / 2f
                            val maxY = fittedHeight * (nextZoom - 1f) / 2f
                            zoom = nextZoom
                            pan = Offset(nextPan.x.coerceIn(-maxX, maxX), nextPan.y.coerceIn(-maxY, maxY))
                        },
                        onResetView = { zoom = 1f; pan = Offset.Zero },
                        message = message, onDismiss = { message = null }
                    )
                }
                if (signing) SignatureDialog(
                    initial = strokes,
                    onCancel = { signing = false },
                    onConfirm = { result -> strokes = result; signatureReady = true; signing = false; signatureCenter = Offset(.5f, .72f) }
                )
                if (help) AlertDialog(
                    onDismissRequest = { help = false },
                    title = { Text("How to use Easy PDF Sign", fontWeight = FontWeight.Bold) },
                    text = { Text("1. Choose a PDF file.\n2. Tap “Add signature” and sign with your finger.\n3. Drag the signature to position it on the page.\n4. Choose a page, then save a new copy.\n\nYour files are processed only on your device.") },
                    confirmButton = { TextButton(onClick = { help = false }) { Text("Got it") } }
                )
        }
    }
}

@Composable
private fun PdfSignatureMark(modifier: Modifier = Modifier, color: Color = Ink) {
    Canvas(modifier) {
        val sx = size.width / 48f
        val sy = size.height / 48f
        val paper = Path().apply {
            moveTo(12f * sx, 5f * sy)
            lineTo(28f * sx, 5f * sy)
            lineTo(37f * sx, 14f * sy)
            lineTo(37f * sx, 41f * sy)
            quadraticTo(37f * sx, 44f * sy, 34f * sx, 44f * sy)
            lineTo(12f * sx, 44f * sy)
            quadraticTo(9f * sx, 44f * sy, 9f * sx, 41f * sy)
            lineTo(9f * sx, 8f * sy)
            quadraticTo(9f * sx, 5f * sy, 12f * sx, 5f * sy)
            close()
        }
        drawPath(paper, color)
        drawLine(Color.White, Offset(15f * sx, 18f * sy), Offset(30f * sx, 18f * sy), 2f * sx)
        drawLine(Color.White, Offset(15f * sx, 23f * sy), Offset(27f * sx, 23f * sy), 2f * sx)
        val signature = Path().apply {
            moveTo(14f * sx, 34f * sy)
            cubicTo(17f * sx, 27f * sy, 18f * sx, 39f * sy, 21f * sx, 32f * sy)
            cubicTo(23f * sx, 28f * sy, 23f * sx, 37f * sy, 27f * sx, 33f * sy)
            lineTo(33f * sx, 29f * sy)
        }
        drawPath(signature, Color(0xFF67A792), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.4f * sx, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
private fun HomeScreen(busy: Boolean, onPick: () -> Unit, onHelp: () -> Unit, message: String?, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(76.dp))
        Box(Modifier.size(88.dp).background(Mint, CircleShape), Alignment.Center) { PdfSignatureMark(Modifier.size(48.dp), Ink) }
        Spacer(Modifier.height(22.dp))
        Text("Easy PDF Sign", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(10.dp))
        Text("Sign PDF documents with ease", fontSize = 16.sp, color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(54.dp))
        Button(onClick = onPick, enabled = !busy, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(16.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
            else Text("Choose a PDF", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = onHelp, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) { Text("How to use", fontSize = 16.sp) }
        Spacer(Modifier.height(36.dp))
        Text("Works offline • Private and secure • Free", fontSize = 13.sp, color = Muted, textAlign = TextAlign.Center)
    }
    if (message != null) AlertDialog(onDismissRequest = onDismiss, text = { Text(message) }, confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } })
}

@Composable
private fun EditorScreen(
    fileName: String, bitmap: Bitmap?, pageIndex: Int, pageCount: Int, signatureReady: Boolean,
    strokes: List<Stroke>, center: Offset, busy: Boolean, zoom: Float, pan: Offset,
    onPrevious: () -> Unit, onNext: () -> Unit, onBack: () -> Unit, onSign: () -> Unit, onSave: () -> Unit,
    onDragSignature: (Offset) -> Unit, onZoom: (Float) -> Unit,
    onTransform: (Float, Offset, Offset, androidx.compose.ui.geometry.Size) -> Unit,
    onResetView: () -> Unit, message: String?, onDismiss: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(fileName, Modifier.weight(1f), maxLines = 1, color = Ink, fontWeight = FontWeight.Medium)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Page ${pageIndex + 1} of $pageCount", color = Muted)
            Row {
                OutlinedButton(onClick = onPrevious, enabled = pageIndex > 0) { Text("Previous") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onNext, enabled = pageIndex < pageCount - 1) { Text("Next") }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { onZoom((zoom / 1.25f).coerceAtLeast(1f)) }, enabled = zoom > 1f) { Text("−") }
            TextButton(onClick = onResetView) { Text("${(zoom * 100).toInt()}%") }
            TextButton(onClick = { onZoom((zoom * 1.25f).coerceAtMost(5f)) }, enabled = zoom < 5f) { Text("+") }
            Text("Pinch to zoom • Drag to pan", color = Muted, fontSize = 11.sp)
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFE8ECE9), RoundedCornerShape(16.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().pointerInput(Unit) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        onTransform(zoomChange, panChange, centroid, androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))
                    }
                }, contentAlignment = Alignment.Center) {
                  val pageRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                  val viewportRatio = maxWidth / maxHeight
                  val pageWidth = if (viewportRatio > pageRatio) maxHeight * pageRatio else maxWidth
                  val pageHeight = if (viewportRatio > pageRatio) maxHeight else maxWidth / pageRatio
                  Box(Modifier.width(pageWidth).height(pageHeight).graphicsLayer {
                      scaleX = zoom
                      scaleY = zoom
                      translationX = pan.x
                      translationY = pan.y
                  }) {
                    Image(bitmap.asImageBitmap(), contentDescription = "PDF page preview", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
                    if (signatureReady) {
                    val currentCenter by rememberUpdatedState(center)
                    Canvas(Modifier.fillMaxSize().pointerInput(bitmap) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val width = size.width.toFloat()
                            val height = size.height.toFloat()
                            val cx = currentCenter.x * width
                            val cy = currentCenter.y * height
                            val withinSignature = down.position.x in (cx - width * .19f)..(cx + width * .19f) &&
                                down.position.y in (cy - height * .07f)..(cy + height * .07f)
                            if (withinSignature) {
                                down.consume()
                                var previous = down.position
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.count { it.pressed } > 1) break
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) { change.consume(); break }
                                    if (change.position != previous) {
                                        val delta = change.position - previous
                                        onDragSignature(Offset(
                                            (currentCenter.x + delta.x / width).coerceIn(.05f, .95f),
                                            (currentCenter.y + delta.y / height).coerceIn(.05f, .95f)
                                        ))
                                        previous = change.position
                                        change.consume()
                                    }
                                }
                            }
                        }
                    }) {
                            val w = size.width * .38f
                            val h = size.height * .14f
                            val left = center.x * size.width - w / 2
                            val top = center.y * size.height - h / 2
                            strokes.forEach { stroke ->
                                if (stroke.size > 1) {
                                    val path = Path().apply { moveTo(left + stroke[0].x * w, top + stroke[0].y * h); stroke.drop(1).forEach { lineTo(left + it.x * w, top + it.y * h) } }
                                    drawPath(path, Color(0xFF142C63), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                                }
                            }
                        }
                    }
                  }
                }
            } else CircularProgressIndicator(color = Ink)
        }
        Spacer(Modifier.height(12.dp))
        if (signatureReady) Text("Drag the signature to position it on the page.", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = Muted, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onSign, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(14.dp)) { Text(if (signatureReady) "Edit signature" else "Add signature") }
            Button(onClick = onSave, enabled = signatureReady && !busy, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(14.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White) else Text("Save PDF")
            }
        }
    }
    if (message != null) AlertDialog(onDismissRequest = onDismiss, text = { Text(message) }, confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } })
}

@Composable
private fun SignatureDialog(initial: List<Stroke>, onCancel: () -> Unit, onConfirm: (List<Stroke>) -> Unit) {
    var currentStrokes by remember { mutableStateOf(initial) }
    var active by remember { mutableStateOf<Stroke?>(null) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Draw your signature", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Use your finger to sign in the box", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Canvas(Modifier.fillMaxWidth().height(190.dp).background(Color.White, RoundedCornerShape(12.dp)).pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { point -> active = listOf(Offset(point.x / size.width, point.y / size.height)) },
                        onDrag = { change, _ -> change.consume(); active = active.orEmpty() + Offset(change.position.x / size.width, change.position.y / size.height) },
                        onDragEnd = { if (!active.isNullOrEmpty()) currentStrokes = currentStrokes + listOf(active!!); active = null },
                        onDragCancel = { active = null }
                    )
                }) {
                    fun drawStroke(stroke: Stroke) {
                        if (stroke.size < 2) return
                        val path = Path().apply { moveTo(stroke[0].x * size.width, stroke[0].y * size.height); stroke.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) } }
                        drawPath(path, Color(0xFF142C63), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    }
                    currentStrokes.forEach(::drawStroke)
                    active?.let(::drawStroke)
                }
            }
        },
        dismissButton = { TextButton(onClick = { currentStrokes = emptyList() }) { Text("Clear") } },
        confirmButton = {
            Row {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = { if (currentStrokes.isNotEmpty()) onConfirm(currentStrokes) }) { Text("Use signature") }
            }
        }
    )
}

private fun signatureBitmap(strokes: List<Stroke>): Bitmap {
    val bitmap = createBitmap(900, 320)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(20, 44, 99)
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    strokes.forEach { stroke ->
        if (stroke.size == 1) canvas.drawCircle(stroke[0].x * 900, stroke[0].y * 320, 4f, paint)
        else if (stroke.size > 1) {
            val path = android.graphics.Path().apply { moveTo(stroke[0].x * 900, stroke[0].y * 320); stroke.drop(1).forEach { lineTo(it.x * 900, it.y * 320) } }
            canvas.drawPath(path, paint)
        }
    }
    return bitmap
}

private fun sameDocument(first: Uri, second: Uri): Boolean {
    if (first == second) return true
    if (first.scheme == "file" && second.scheme == "file") return first.path == second.path
    if (first.scheme != "content" || second.scheme != "content" || first.authority != second.authority) return false
    return try {
        DocumentsContract.getDocumentId(first) == DocumentsContract.getDocumentId(second)
    } catch (_: IllegalArgumentException) {
        false
    }
}
