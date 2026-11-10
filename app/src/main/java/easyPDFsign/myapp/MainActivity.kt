package easyPDFsign.myapp

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.app.Activity
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.core.view.WindowCompat
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
private val HomeInk = Color(0xFF251D22)
private val HomePaper = Color(0xFFFFFCFC)
private val HomeMuted = Color(0xFF55565D)
private val Accent = Color(0xFFD5000B)
private val ViewerRed = Color(0xFFD50000)
private val ViewerBrightRed = Color(0xFFF20D18)
private val ViewerDarkRed = Color(0xFFB80000)
private val ViewerBackground = Color(0xFFFFFCFC)
private val ViewerLightRed = Color(0xFFFFF1F2)
private val ViewerText = Color(0xFF241D21)
private val ViewerSecondaryText = Color(0xFF66666B)
private val ViewerLightGray = Color(0xFFE8E8E8)
private val ViewerDisabled = Color(0xFFF1F1F1)
private val ViewerBorder = Color(0xFFE8CBCD)
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

    SideEffect {
        (context as? Activity)?.window?.let { window ->
            window.statusBarColor = android.graphics.Color.rgb(255, 252, 252)
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        }
    }

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
private fun HomeScreen(busy: Boolean, onPick: () -> Unit, onHelp: () -> Unit, message: String?, onDismiss: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(HomePaper).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val compact = maxHeight < 720.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = if (maxWidth < 360.dp) 20.dp else 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(if (compact) 10.dp else 22.dp))
            HomeHero(Modifier.fillMaxWidth().height(if (compact) 210.dp else 270.dp))
            Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
            Text("Easy PDF Sign", fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = HomeInk, textAlign = TextAlign.Center)
            Spacer(Modifier.height(7.dp))
            Text("Sign PDF documents with ease", fontSize = 19.sp, lineHeight = 26.sp, color = HomeMuted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(if (compact) 26.dp else 48.dp))
            Button(
                onClick = onPick, enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(18.dp))
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White)
                    else ActionGlyph("folder", Modifier.size(30.dp), Color.White)
                    Text("Choose a PDF", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    ActionGlyph("chevron", Modifier.size(26.dp), Color.White)
                    Spacer(Modifier.width(12.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onHelp, modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE34A52)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HomeInk)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(18.dp))
                    ActionGlyph("info", Modifier.size(28.dp), Accent)
                    Text("How to use", Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    ActionGlyph("chevron", Modifier.size(26.dp), Accent)
                    Spacer(Modifier.width(12.dp))
                }
            }
            Spacer(Modifier.height(if (compact) 30.dp else 46.dp))
            FeatureRow()
            Spacer(Modifier.height(22.dp))
        }
    }
    if (message != null) AlertDialog(onDismissRequest = onDismiss, text = { Text(message) }, confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } })
}

@Composable
private fun HomeHero(modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { contentDescription = "PDF document with a signature and pen" }) {
        val sx = size.width / 360f
        val sy = size.height / 360f
        fun p(x: Float, y: Float) = Offset(x * sx, y * sy)
        val pink = Color(0xFFFFE4E8)
        drawCircle(Brush.radialGradient(listOf(pink.copy(alpha = .92f), pink.copy(alpha = .34f), Color.Transparent), center = p(180f, 178f), radius = 174f * sx), radius = 174f * sx, center = p(180f, 178f))

        // Paper silhouette and its folded corner.
        val paper = Path().apply {
            moveTo(122f*sx, 36f*sy); lineTo(224f*sx, 36f*sy); lineTo(276f*sx, 88f*sy)
            lineTo(276f*sx, 296f*sy); cubicTo(276f*sx, 313f*sy, 267f*sx, 322f*sy, 250f*sx, 322f*sy)
            lineTo(110f*sx, 322f*sy); cubicTo(94f*sx, 322f*sy, 85f*sx, 313f*sy, 85f*sx, 296f*sy)
            lineTo(85f*sx, 62f*sy); cubicTo(85f*sx, 45f*sy, 95f*sx, 36f*sy, 112f*sx, 36f*sy); close()
        }
        drawPath(paper, Color(0x22A90008), style = DrawStroke(width = 13f * sx))
        drawPath(paper, Color.White)
        drawPath(paper, Color(0xFFB9000A), style = DrawStroke(width = 7f * sx))
        val fold = Path().apply { moveTo(224f*sx, 40f*sy); lineTo(224f*sx, 75f*sy); quadraticTo(224f*sx, 88f*sy, 238f*sx, 88f*sy); lineTo(271f*sx, 88f*sy) }
        drawPath(fold, Color(0xFFD9D8DD), style = DrawStroke(width = 3f * sx))

        // Document lines.
        listOf(151f to 112f, 151f to 132f, 151f to 152f).forEachIndexed { index, pair ->
            drawRoundRect(Color(0xFFB9BCC5), p(pair.first, pair.second), androidx.compose.ui.geometry.Size((index == 2).let { if (it) 92f else 139f } * sx, 8f * sy), androidx.compose.ui.geometry.CornerRadius(4f * sx))
        }

        // Red PDF badge.
        drawRoundRect(Color(0x22000000), p(44f, 78f), androidx.compose.ui.geometry.Size(150f*sx, 59f*sy), androidx.compose.ui.geometry.CornerRadius(16f*sx))
        drawRoundRect(Brush.linearGradient(listOf(Color(0xFFFF202A), Color(0xFFC90008)), p(45f, 78f), p(194f, 137f)), p(40f, 73f), androidx.compose.ui.geometry.Size(154f*sx, 60f*sy), androidx.compose.ui.geometry.CornerRadius(16f*sx))
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE; textSize = 36f * sx; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD); textAlign = android.graphics.Paint.Align.CENTER
            }
            canvas.nativeCanvas.drawText("PDF", 117f * sx, 115f * sy, paint)
        }

        // Hand-drawn signature.
        val sign = Path().apply {
            moveTo(116f*sx, 264f*sy); cubicTo(127f*sx, 235f*sy, 163f*sx, 224f*sy, 165f*sx, 234f*sy)
            cubicTo(167f*sx, 243f*sy, 126f*sx, 273f*sy, 124f*sx, 284f*sy); cubicTo(122f*sx, 295f*sy, 155f*sx, 271f*sy, 174f*sx, 256f*sy)
            cubicTo(195f*sx, 239f*sy, 179f*sx, 270f*sy, 174f*sx, 277f*sy); cubicTo(195f*sx, 260f*sy, 210f*sx, 256f*sy, 203f*sx, 274f*sy)
            cubicTo(218f*sx, 266f*sy, 224f*sx, 264f*sy, 233f*sx, 264f*sy); moveTo(164f*sx, 287f*sy); cubicTo(190f*sx, 282f*sy, 212f*sx, 282f*sy, 237f*sx, 284f*sy)
        }
        drawPath(sign, Accent, style = DrawStroke(width = 4f * sx, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))

        // Pen with gold trim, angled toward the signature.
        val pen = Path().apply {
            moveTo(275f*sx, 103f*sy); cubicTo(280f*sx, 96f*sy, 289f*sx, 98f*sy, 292f*sx, 105f*sy)
            lineTo(299f*sx, 117f*sy); lineTo(246f*sx, 263f*sy); lineTo(231f*sx, 280f*sy); lineTo(232f*sx, 256f*sy); close()
        }
        drawPath(pen, Color(0xFF121316))
        drawPath(pen, Color(0xFFFFB52E), style = DrawStroke(width = 2.7f * sx))
        drawLine(Color(0xFFFFB52E), p(286f, 111f), p(240f, 244f), 3.5f * sx, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(Color(0xFFE0000A), p(254f, 227f), p(269f, 233f), 7f * sx, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(Color(0xFFFFC04B), p(247f, 247f), p(259f, 251f), 5f * sx, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawPath(Path().apply { moveTo(231f*sx, 280f*sy); lineTo(245f*sx, 264f*sy); lineTo(232f*sx, 256f*sy); close() }, Color(0xFFE8A52E))
    }
}

@Composable
private fun ActionGlyph(kind: String, modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier.semantics {
        contentDescription = when (kind) {
            "folder" -> "Folder"
            "info" -> "Information"
            "back" -> "Back"
            "minus" -> "Zoom out"
            "plus" -> "Zoom in"
            "pen" -> "Add signature"
            "save" -> "Save"
            else -> "Continue"
        }
    }) {
        val s = size.minDimension / 24f
        fun o(x: Float, y: Float) = Offset(x * s, y * s)
        when (kind) {
            "folder" -> {
                val path = Path().apply { moveTo(3f*s, 6f*s); lineTo(10f*s, 6f*s); lineTo(12f*s, 8.5f*s); lineTo(21f*s, 8.5f*s); quadraticTo(22f*s, 8.5f*s, 22f*s, 10f*s); lineTo(22f*s, 19f*s); quadraticTo(22f*s, 20f*s, 21f*s, 20f*s); lineTo(3f*s, 20f*s); quadraticTo(2f*s, 20f*s, 2f*s, 19f*s); lineTo(2f*s, 7f*s); quadraticTo(2f*s, 6f*s, 3f*s, 6f*s); close() }
                drawPath(path, color, style = DrawStroke(2.2f*s, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                drawLine(color, o(2f, 10f), o(22f, 10f), 2f*s)
            }
            "info" -> {
                drawCircle(color, radius = 9.5f*s, style = DrawStroke(2f*s))
                drawLine(color, o(12f, 11f), o(12f, 17f), 2f*s, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawCircle(color, radius = 1.2f*s, center = o(12f, 7.5f))
            }
            "back" -> {
                drawPath(Path().apply { moveTo(11f*s, 5f*s); lineTo(4f*s, 12f*s); lineTo(11f*s, 19f*s); moveTo(4f*s, 12f*s); lineTo(20f*s, 12f*s) }, color, style = DrawStroke(2.2f*s, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            "minus" -> drawLine(color, o(5f, 12f), o(19f, 12f), 2.2f*s, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            "plus" -> {
                drawLine(color, o(5f, 12f), o(19f, 12f), 2.2f*s, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(color, o(12f, 5f), o(12f, 19f), 2.2f*s, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            "pen" -> drawPath(Path().apply { moveTo(4f*s, 17f*s); lineTo(5.5f*s, 20f*s); lineTo(8.5f*s, 18.5f*s); lineTo(19f*s, 8f*s); lineTo(15f*s, 4f*s); close(); moveTo(13.5f*s, 5.5f*s); lineTo(17.5f*s, 9.5f*s) }, color, style = DrawStroke(2f*s, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            "save" -> {
                drawRoundRect(color, o(4f, 3f), androidx.compose.ui.geometry.Size(16f*s, 18f*s), androidx.compose.ui.geometry.CornerRadius(2f*s), style = DrawStroke(2f*s))
                drawLine(color, o(8f, 4f), o(8f, 9f), 2f*s)
                drawLine(color, o(16f, 4f), o(16f, 9f), 2f*s)
                drawRoundRect(color, o(8f, 13f), androidx.compose.ui.geometry.Size(8f*s, 8f*s), androidx.compose.ui.geometry.CornerRadius(1f*s), style = DrawStroke(1.8f*s))
            }
            "chevron" -> drawPath(Path().apply { moveTo(9f*s, 5f*s); lineTo(16f*s, 12f*s); lineTo(9f*s, 19f*s) }, color, style = DrawStroke(2.4f*s, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

@Composable
private fun FeatureRow() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Feature("offline", "Works offline", Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(36.dp).background(Color(0xFFD5D3D5)))
        Feature("shield", "Private and secure", Modifier.weight(1.2f))
        Box(Modifier.width(1.dp).height(36.dp).background(Color(0xFFD5D3D5)))
        Feature("gift", "Free", Modifier.weight(.8f))
    }
}

@Composable
private fun Feature(kind: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Canvas(Modifier.size(30.dp).semantics { contentDescription = "" }) {
            val s = size.minDimension / 24f
            fun pt(x: Float, y: Float) = Offset(x*s, y*s)
            when (kind) {
                "offline" -> {
                    drawPath(Path().apply { moveTo(3f*s, 9f*s); quadraticTo(12f*s, 1f*s, 21f*s, 9f*s); moveTo(6f*s, 12f*s); quadraticTo(12f*s, 6f*s, 18f*s, 12f*s); moveTo(9.5f*s, 15.5f*s); quadraticTo(12f*s, 13f*s, 14.5f*s, 15.5f*s) }, Accent, style = DrawStroke(2.1f*s, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    drawLine(Accent, pt(3f, 3f), pt(21f, 21f), 2.2f*s, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
                "shield" -> {
                    val path = Path().apply { moveTo(12f*s, 2f*s); lineTo(20f*s, 5f*s); lineTo(19f*s, 14f*s); quadraticTo(18f*s, 19f*s, 12f*s, 22f*s); quadraticTo(6f*s, 19f*s, 5f*s, 14f*s); lineTo(4f*s, 5f*s); close() }
                    drawPath(path, Accent, style = DrawStroke(2f*s, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    drawPath(Path().apply { moveTo(8.5f*s, 12f*s); lineTo(11f*s, 14.5f*s); lineTo(16f*s, 9.5f*s) }, Accent, style = DrawStroke(2f*s, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
                else -> {
                    drawRoundRect(Accent, pt(4f, 8f), androidx.compose.ui.geometry.Size(16f*s, 13f*s), androidx.compose.ui.geometry.CornerRadius(2f*s), style = DrawStroke(2f*s))
                    drawRect(Accent, pt(3f, 6f), androidx.compose.ui.geometry.Size(18f*s, 3f*s), style = DrawStroke(2f*s))
                    drawLine(Accent, pt(12f, 6f), pt(12f, 21f), 2f*s)
                    drawPath(Path().apply { moveTo(12f*s, 7f*s); cubicTo(5f*s, 7f*s, 6f*s, 2f*s, 9f*s, 3f*s); cubicTo(11f*s, 4f*s, 12f*s, 7f*s, 12f*s, 7f*s); cubicTo(12f*s, 7f*s, 13f*s, 4f*s, 16f*s, 3f*s); cubicTo(19f*s, 2f*s, 19f*s, 7f*s, 12f*s, 7f*s) }, Accent, style = DrawStroke(1.8f*s, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
        }
        Text(label, fontSize = 13.sp, lineHeight = 16.sp, color = Color(0xFF35343A), textAlign = TextAlign.Center, maxLines = 2)
    }
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
    Column(
        Modifier.fillMaxSize()
            .background(ViewerBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                ActionGlyph("back", Modifier.size(24.dp), ViewerDarkRed)
            }
            Text(
                fileName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = ViewerText, fontWeight = FontWeight.Medium
            )
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Page ${pageIndex + 1} of $pageCount", color = ViewerText, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPrevious, enabled = pageIndex > 0,
                    modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ViewerRed, contentColor = Color.White,
                        disabledContainerColor = ViewerDisabled, disabledContentColor = ViewerSecondaryText
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) { Text("Previous") }
                Button(
                    onClick = onNext, enabled = pageIndex < pageCount - 1,
                    modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ViewerRed, contentColor = Color.White,
                        disabledContainerColor = ViewerDisabled, disabledContentColor = ViewerSecondaryText
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) { Text("Next") }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { onZoom((zoom / 1.25f).coerceAtLeast(1f)) }, enabled = zoom > 1f) {
                ActionGlyph("minus", Modifier.size(22.dp), if (zoom > 1f) ViewerRed else ViewerSecondaryText)
            }
            TextButton(onClick = onResetView, colors = ButtonDefaults.textButtonColors(contentColor = ViewerText)) {
                Text("${(zoom * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = { onZoom((zoom * 1.25f).coerceAtMost(5f)) }, enabled = zoom < 5f) {
                ActionGlyph("plus", Modifier.size(22.dp), if (zoom < 5f) ViewerRed else ViewerSecondaryText)
            }
            Text("Pinch to zoom • Drag to pan", Modifier.weight(1f), color = ViewerSecondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ViewerLightRed)
                .border(1.dp, ViewerBorder, RoundedCornerShape(16.dp))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
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
            } else CircularProgressIndicator(color = ViewerRed)
        }
        Spacer(Modifier.height(12.dp))
        if (signatureReady) Text("Drag the signature to position it on the page.", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = ViewerSecondaryText, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onSign, modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, ViewerRed),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = ViewerLightRed, contentColor = ViewerRed),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionGlyph("pen", Modifier.size(22.dp), ViewerBrightRed)
                    Text(if (signatureReady) "Edit signature" else "Add signature", maxLines = 1)
                }
            }
            Button(
                onClick = onSave, enabled = signatureReady && !busy,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ViewerRed, contentColor = Color.White,
                    disabledContainerColor = ViewerDisabled, disabledContentColor = ViewerSecondaryText
                )
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionGlyph("save", Modifier.size(22.dp), if (signatureReady) Color.White else ViewerSecondaryText)
                    Text("Save PDF", maxLines = 1)
                }
            }
        }
    }
    if (message != null) AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        containerColor = ViewerBackground,
        titleContentColor = ViewerText,
        textContentColor = ViewerSecondaryText,
        confirmButton = { TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = ViewerRed)) { Text("OK") } }
    )
}

@Composable
private fun SignatureDialog(initial: List<Stroke>, onCancel: () -> Unit, onConfirm: (List<Stroke>) -> Unit) {
    var currentStrokes by remember { mutableStateOf(initial) }
    var active by remember { mutableStateOf<Stroke?>(null) }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val drawingHeight = (screenHeight * .24f).coerceIn(120.dp, 190.dp)
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.heightIn(max = screenHeight * .92f),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 6.dp,
        containerColor = ViewerBackground,
        titleContentColor = ViewerText,
        textContentColor = ViewerSecondaryText,
        title = { Text("Draw your signature", fontWeight = FontWeight.Bold, color = ViewerText) },
        text = {
            Column(Modifier.heightIn(max = screenHeight * .5f).verticalScroll(rememberScrollState())) {
                Text("Use your finger to sign in the box", color = ViewerSecondaryText, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Canvas(
                    Modifier.fillMaxWidth().height(drawingHeight)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, ViewerLightGray, RoundedCornerShape(12.dp))
                        .semantics { contentDescription = "Signature drawing area" }
                        .pointerInput(Unit) {
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
        dismissButton = {
            TextButton(
                onClick = { currentStrokes = emptyList() },
                colors = ButtonDefaults.textButtonColors(contentColor = ViewerRed)
            ) { Text("Clear") }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.textButtonColors(contentColor = ViewerSecondaryText)
                ) { Text("Cancel") }
                Button(
                    onClick = { if (currentStrokes.isNotEmpty()) onConfirm(currentStrokes) },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ViewerRed, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) { Text("Use signature", maxLines = 1) }
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
