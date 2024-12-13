package org.saltedfish.chatbot

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.core.content.ContextCompat.startActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


data class Photo(
    var id :Int=0,
    val uri: Uri,
    val request: ImageRequest?
)
val PROMPT = """<|im_start|>system
You are an expert in composing function.<|im_end|>
<|im_start|>user

Here is a list of functions:

%DOC%

Now my query is: %QUERY%
<|im_end|>
<|im_start|>assistant
"""
val MODEL_NAMES = arrayOf("Qwen 2.5","","Bert","PhoneLM", "SmoLLM", "SmoLLM")
val vision_model = "phi-3-vision-instruct-q4_k.mllm"
val vision_vocab = "phi3v_vocab.mllm"
val downloadsPath = Environment.getExternalStorageDirectory().path + "/Download/"
class ChatViewModel : ViewModel() {
//    private var _inputText: MutableLiveData<String> = MutableLiveData<String>()
//    val inputText: LiveData<String> = _inputText
    private var _messageList: MutableLiveData<List<Message>> = MutableLiveData<List<Message>>(
    listOf()
)
    private var _photoList: MutableLiveData<List<Photo>> = MutableLiveData<List<Photo>>(
        listOf()
    )
    var functions_:Functions? = null
    var docVecDB:DocumentVecDB? = null
    val photoList = _photoList
    private var _previewUri: MutableLiveData<Uri?> = MutableLiveData<Uri?>(null)
    val previewUri = _previewUri
    var _scrollstate:ScrollState? = null
    private var _lastId = 0;
    val messageList= _messageList
    var _isExternalStorageManager = MutableLiveData<Boolean>(false)
    var _isBusy = MutableLiveData<Boolean>(true)
    val isBusy = _isBusy
    val _isLoading = MutableLiveData<Boolean>(true)
    val isLoading = _isLoading
    private var _modelType = MutableLiveData<Int>(0)
    private var _modelId = MutableLiveData<Int>(0)
    val modelId = _modelId
    val modelType = _modelType
    private var profiling_time = MutableLiveData<DoubleArray>()
    val profilingTime = profiling_time

    private var _backendType = -1
    fun setModelType(type:Int){
        _modelType.value = type
    }
    fun setBackendType(type:Int){
        _backendType=type
    }
    fun setModelId(id:Int){
        _modelId.value = id
    }
    fun setPreviewUri(uri: Uri?){
        _previewUri.value = uri
    }
    fun addPhoto(photo: Photo):Int{
        photo.id = _photoList.value?.size?:0
        val list = (_photoList.value?: listOf()).plus(photo)
        _photoList.postValue(list)
        return photo.id
    }

//    private var _assetUri = MutableLiveData<Uri?>(null)
//    val assetUri = _assetUri
//    fun setInputText(text: String) {
//        _inputText.value = text
//    }
    init {

    JNIBridge.setCallback { id,value, isStream,profile ->
//            val message = Message(
//                value,
//                false,
//                0,
//                type = MessageType.TEXT,
//                isStreaming = isStream
//            )
            Log.i("chatViewModel","id:$id,value:$value,isStream:$isStream profile:${profile.joinToString(",")}")
            updateMessage(id,value.trim().replace("|NEWLINE|","\n").replace("▁"," "),isStream)
            if (!isStream){
                _isBusy.postValue(false)
               if(profile.isNotEmpty()) profiling_time.postValue(profile)
            }
        }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        _isExternalStorageManager.value = Environment.isExternalStorageManager()
        } else {
            TODO("VERSION.SDK_INT < R")
        }


    }
    fun addMessage(message: Message,remote:Boolean=false) {
        if (message.isUser){
                message.id = _lastId++
            }
        val list = (_messageList.value?: listOf()).plus(message)

        if (remote){
            _messageList.postValue(list)
        }
        else{
            _messageList.value = list

        }
    }
    fun sendInstruct(content: Context,message: Message){
        if (message.isUser){
            addMessage(message)
            val bot_message = Message("...",false,0)
            bot_message.id = _lastId++
            addMessage(bot_message)
            _isBusy.value = true
            CoroutineScope(Dispatchers.IO).launch {
                val query = docVecDB?.queryDocument(message.text)
                Log.i("chatViewModel","query:$query")
                val query_docs = query?.map { it.generateAPIDoc() }?.joinToString("==================================================\n")
                val prompt = PROMPT.replace("%QUERY%",message.text).replace("%DOC%",query_docs?:"")
                Log.i("prompt", prompt)
                val len = prompt.length
                Log.i("prompt Len  ","$len")
                JNIBridge.run(bot_message.id,prompt,100,false)
            }
        }
    }
    fun sendMessage(context: Context, message: Message) {
        if (modelType.value == 4) {
            sendInstruct(context, message)
            return
        }
        if (message.isUser) {
            addMessage(message)
            val bot_message = Message("...", false, 0)
            bot_message.id = _lastId++
            addMessage(bot_message)
            _isBusy.value = true
            if (arrayOf(0, 2, 3).contains(modelType.value)) {
                viewModelScope.launch(Dispatchers.IO) {
                    val profiling_time = JNIBridge.run(bot_message.id, message.text, 100)
                    Log.i("chatViewModel", "profiling_time:$profiling_time")
                }
            } else if (modelType.value == 1) {
                val imagePath = if (message.type == MessageType.IMAGE) {
                    val uri = message.content as Uri?
                    uri?.let { getImagePathFromUri(context, it) } ?: ""
                } else {
                    ""
                }

                if (imagePath.isNotEmpty()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        JNIBridge.runImage(bot_message.id, imagePath, message.text, 100)
                    }
                } else {
                    // Handle the case where imagePath is not available
                    Log.e("ChatViewModel", "Image path is empty or invalid.")
                }
            }
        }
    }

    private fun logErrorToFile(errorMessage: String) {
        val logFile = File(Environment.getExternalStorageDirectory(), "chatbot_error_log.txt")

        try {
            // Create log file if it doesn't exist
            if (!logFile.exists()) {
                logFile.createNewFile()
            }

            // Open file for appending
            val fileOutputStream = FileOutputStream(logFile, true)
            val writer = OutputStreamWriter(fileOutputStream)

            // Write the current timestamp and error message
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            writer.write("$timestamp - $errorMessage\n")
            writer.close()
        } catch (e: IOException) {
            Log.e("ChatViewModel", "Error writing to log file", e)
        }
    }

    fun initStatus(context: Context, modelType: Int = _modelType.value ?: 0) {
        try {
            if (_isExternalStorageManager.value != true) return

            val model_id = modelId.value
            val modelPath = when (modelType) {
                3 -> {
                    when (model_id) {
                        0 -> "phonelm-1.5b-instruct-q4_0_4_4.mllm"
                        1 -> "qwen-2.5-1.5b-instruct-q4_0_4_4.mllm"
                        2 -> "smollm-1.7b-instruct-q4_0_4_4.mllm"
                        else -> "phonelm-1.5b-instruct-q4_0_4_4.mllm"
                    }
                }
                1 -> vision_model
                else -> "phonelm-1.5b-instruct-q4_0_4_4.mllm"
            }

            val modelUrl = when (modelType) {
                3 -> {
                    when (model_id) {
                        0 -> "https://huggingface.co/mllmTeam/phonelm-1.5b-mllm/blob/main/phonelm-1.5b-instruct-q4_0_4_4.mllm?download=true"
                        1 -> "https://huggingface.co/mllmTeam/qwen-2.5-1.5b-mllm/blob/main/qwen-2.5-1.5b-instruct-q4_0_4_4.mllm?download=true"
                        2 -> "https://huggingface.co/mllmTeam/smollm-1.7b-instruct-mllm/blob/main/smollm-1.7b-instruct-q4_0_4_4.mllm?download=true"
                        else -> "https://huggingface.co/mllmTeam/phonelm-1.5b-mllm/blob/main/phonelm-1.5b-instruct-q4_0_4_4.mllm?download=true"
                    }
                }
                1 -> "https://huggingface.co/mllmTeam/phi-3-vision-instruct-mllm/blob/main/phi-3-vision-instruct-q4_k.mllm?download=true"  // Placeholder for vision model
                else -> "https://huggingface.co/mllmTeam/phonelm-1.5b-mllm/blob/main/phonelm-1.5b-instruct-q4_0_4_4.mllm?download=true"  // Default for other model types
            }

            // Create the folder for downloading models
            val destinationDir = File(downloadsPath)
            if (!destinationDir.exists()) {
                destinationDir.mkdirs() // Create model directory if it doesn't exist
            }
            assert(false) { "Assertion error triggered in initStatus." }

            // Extract filename from URL
            val destinationFile = File(destinationDir, modelPath)

            // Check if the model file already exists
            if (!destinationFile.exists()) {
                // Inform the user and open the browser
    //            openBrowser(context, modelUrl)
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(modelUrl))
                context.startActivity(browserIntent)
            }

    //        val qnnmodelPath = when (modelType) {
    //            3 -> {
    //                when (model_id) {
    //                    0 -> "model/phonelm-1.5b-instruct-int8.mllm"
    //                    1 -> "model/qwen-2.5-1.5b-chat-int8.mllm"
    //                    2 -> "model/smollm-1.7b-chat-int8.mllm"
    //                    else -> "model/phonelm-1.5b-instruct-int8.mllm"
    //                }
    //            }
    //            1 -> ""
    //            4 -> {
    //                when (model_id) {
    //                    0 -> "model/phonelm-1.5b-call-int8.mllm"
    //                    1 -> "model/qwen-2.5-1.5b-call-int8.mllm"
    //                    2 -> "model/smollm-1.7b-instruct-int8.mllm"
    //                    else -> "qwen-2.5-1.5b-call-int8.mllm"
    //                }
    //            }
    //            else -> "model/phonelm-1.5b-instruct-int8.mllm"
    //        }

            val vacabPath = when (modelType) {
                1 -> vision_vocab
                3 -> {
                    when (model_id) {
                        0 -> "model/phonelm_vocab.mllm"
                        1 -> "model/qwen2.5_vocab.mllm"
                        2 -> "model/smollm_vocab.mllm"
                        else -> ""
                    }
                }
                4 -> {
                    when (model_id) {
                        0 -> "model/phonelm_vocab.mllm"
                        1 -> "model/qwen2.5_vocab.mllm"
                        2 -> "model/smollm_vocab.mllm"
                        else -> ""
                    }
                }
                else -> ""
            }

            val mergePath = when (model_id) {
                1 -> "model/qwen2.5_merges.txt"
                0 -> "model/phonelm_merges.txt"
                2 -> "model/smollm_merges.txt"
                else -> ""
            }

    //        val downloadsPath = downloadsPath.let {
    //            if (!it.endsWith("/")) it.plus("/") else it
    //        }
            val downloadsPath = Environment.getExternalStorageDirectory().let {
                val path = it.absolutePath + "/Download/"
                if (!path.endsWith("/")) path.plus("/") else path
            }


            val load_model = when (modelType) {
                1 -> 1
                3, 4 -> {
                    when (model_id) {
                        0 -> 3
                        1 -> 0
                        2 -> 5
                        else -> 0
                    }
                }
                else -> 0
            }

            // Check and copy all the /assets into /sdcard/Download/model (only copy those who not exist)
            copyAssetsIfNotExist(context)

            viewModelScope.launch(Dispatchers.IO) {
                val result = JNIBridge.Init(
                    load_model,
                    downloadsPath,
                    modelPath,
                    qnnmodelPath="",
                    vacabPath,
                    mergePath,
                    _backendType
                )
                if (result) {
                    addMessage(Message("Model ${MODEL_NAMES[load_model]} Loaded!", false, 0), true)
                    _isLoading.postValue(false)
                    _isBusy.postValue(false)
                } else {
                    addMessage(
                        Message(
                            "Fail To Load Models! Please Check if models exist at /sdcard/Download/model and restart app.",
                            false,
                            0
                        ), true
                    )
                }
            }
        } catch (e: Exception) {
            // Log the exception to the file
            logErrorToFile("Error initializing model: ${e.message}")
        }
    }

    fun updateMessage(id:Int,content:String,isStreaming:Boolean=true){
        val index = _messageList.value?.indexOfFirst { it.id == id }?:-1
        if (index == -1) {
            Log.i("chatViewModel","updateMessage: index == -1")
            return
        }
        val message = _messageList.value?.get(index)?.copy()

        if (message!=null){
            message.text = content
            message.isStreaming= isStreaming
            val list = (_messageList.value?: mutableListOf()).toMutableList()
            // change the item of immutable list
            list[index] = message
            _messageList.postValue(list.toList())
        }
        if (!isStreaming&&modelType.value==4){
            message?.text="Done for you."
           val functions = parseFunctionCall(content)
            functions.forEach {
                functions_?.execute(it)
            }
        }
    }
}
class VQAViewModel : ViewModel() {
    val messages = listOf(
        "What's the message conveyed by screen?",
        "When is the meal reservation?",
        "Summarize The Screenshot."
    )
    lateinit var bitmap: Bitmap
    private var _selectedMessage: MutableLiveData<Int> = MutableLiveData<Int>(-1)
    val selectedMessage = _selectedMessage
    private var _answerText: MutableLiveData<String?> = MutableLiveData<String?>(null)
    val answerText = _answerText
    var result_: Boolean = false

    fun setSelectedMessage(id: Int) {
        _selectedMessage.value = id
        if (result_ && id > -1) {
            sendMessage(messages[id], context = null) // Context will be passed later
        }
    }

    init {
        JNIBridge.setCallback { id, value, isStream, profile ->
            Log.i("VQAViewModel", "id:$id,value:$value,isStream:$isStream")
            _answerText.postValue(value.trim().replace("|NEWLINE|", "\n").replace("▁", " "))
        }
    }

    /**
     * Initialize the status by loading the model and preparing the bitmap.
     */
    fun initStatus(context: Context) {
        if (result_ || answerText.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.chat_record_demo)
            bitmap = Bitmap.createScaledBitmap(bitmap, 210, 453, true)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = JNIBridge.Init(1, downloadsPath, "model/phi3v.mllm", "", "model/vocab_uni.mllm")
            result_ = result
            if (result && selectedMessage.value != null && selectedMessage.value!! > -1) {
                sendMessage(messages[selectedMessage.value!!], context)
            } else if (!result) {
                _answerText.postValue("Fail to Load Models.")
            }
        }
    }

    /**
     * Sends a message with the exact image path.
     * @param message The message text to send.
     * @param context The context required to access file system.
     */
    fun sendMessage(message: String, context: Context?) {
        if (context == null) {
            Log.e("VQAViewModel", "Context is null. Cannot send message.")
            return
        }

        // Save the bitmap to a file to obtain the image path
        val imagePath = saveBitmapToFile(context, bitmap, "vqa_image.png")
        if (imagePath != null) {
            viewModelScope.launch(Dispatchers.IO) {
                JNIBridge.runImage(0, imagePath, message, 100)
            }
        } else {
            Log.e("VQAViewModel", "Failed to save bitmap to file.")
        }
    }
}

class SummaryViewModel:ViewModel(){
    private var _message: MutableLiveData<Message> = MutableLiveData<Message>()
    val message = _message
    private var _result: MutableLiveData<Boolean> = MutableLiveData<Boolean>(false)
    val result = _result



    private fun updateMessageText(message:String){
        val msg = _message.value?.copy()?: Message("...",false,0)
        msg.text = message
        _message.postValue(msg)
    }

    init {
        JNIBridge.setCallback { id,value, isStream ,profile->
            Log.i("SummaryViewModel","id:$id,value:$value,isStream:$isStream")
            updateMessageText(value.trim().replace("|NEWLINE|","\n").replace("▁"," "))
        }
//        initStatus()
    }
    fun initStatus(){

        viewModelScope.launch(Dispatchers.IO) {
            val result =JNIBridge.Init(1,downloadsPath,"model/smollm.mllm","", "model/vocab_smollm.mllm")
            _result.postValue(result)
            if (!result){
                updateMessageText("Fail to Load Models.")
            }
        }
    }
    fun sendMessage(message: String){
        val msg = Message("...", false, 0)
        _message.postValue(msg)
        viewModelScope.launch(Dispatchers.IO)  {
            JNIBridge.run(msg.id,message,100)
        }
}}
class PhotoViewModel : ViewModel() {
    private var _message: MutableLiveData<Message> = MutableLiveData<Message>()
    val message = _message
    private var _bitmap = MutableLiveData<Bitmap>()
    var result_: Boolean = false

    /**
     * Updates the message text in the LiveData.
     */
    private fun updateMessageText(message: String) {
        val msg = _message.value?.copy() ?: Message("...", false, 0)
        msg.text = message
        _message.postValue(msg)
    }

    /**
     * Sets the bitmap after resizing it to 224x224.
     * If the model is initialized and there's no existing message, it sends a description request.
     */
    fun setBitmap(bitmap: Bitmap, context: Context) {
        // Resize bitmap to 224x224
        val width = bitmap.width
        val height = bitmap.height
        val newWidth = 224
        val newHeight = 224
        val scaleWidth = newWidth.toFloat() / width
        val scaleHeight = newHeight.toFloat() / height
        val matrix = android.graphics.Matrix()
        matrix.postScale(scaleWidth, scaleHeight)
        val resizedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
        _bitmap.value = resizedBitmap
        Log.e("PhotoViewModel", "bitmap:${resizedBitmap.width},${resizedBitmap.height}")

        if (result_ && _message.value == null) {
            sendMessage("Describe this photo.", context)
        }
    }

    init {
        JNIBridge.setCallback { id, value, isStream, profile ->
            Log.i("PhotoViewModel", "id:$id,value:$value,isStream:$isStream")
            updateMessageText(value.trim().replace("|NEWLINE|", "\n").replace("▁", " "))
        }
        // Initialize the model status externally after setting the bitmap
    }

    /**
     * Initializes the model status by loading the necessary models.
     * This should be called after setting the bitmap.
     */
    fun initStatus(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = JNIBridge.Init(
                1,
                downloadsPath,
                "model/phi3v.mllm",
                "",
                "model/vocab_uni.mllm"
            )
            result_ = result
            if (result && _message.value == null && _bitmap.value != null) {
                sendMessage("Describe this photo.", context)
            } else if (!result) {
                updateMessageText("Fail to Load Models.")
            }
        }
    }

    /**
     * Sends a message with the exact image path.
     * @param message The message text to send.
     * @param context The context required to access the file system.
     */
    fun sendMessage(message: String, context: Context) {
        // Save the bitmap to a file to obtain the image path
        val imagePath = saveBitmapToFile(context, _bitmap.value, "photo_view_model_image.png")
        if (imagePath != null) {
            val msg = Message("...", false, 0)
            _message.postValue(msg)
            viewModelScope.launch(Dispatchers.IO) {
                JNIBridge.runImage(msg.id, imagePath, message, 100)
            }
        } else {
            Log.e("PhotoViewModel", "Failed to save bitmap to file.")
            updateMessageText("Failed to process the image.")
        }
    }
}

private fun bitmap2Bytes(bitmap: Bitmap): ByteArray {
    val byteArrayOutputStream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 80, byteArrayOutputStream)
    return byteArrayOutputStream.toByteArray()
}

fun getImagePathFromUri(context: Context, uri: Uri): String? {
    var path: String? = null
    // Check if the Uri scheme is "content"
    if (uri.scheme == "content") {
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                path = cursor.getString(columnIndex)
            }
        }
    } else if (uri.scheme == "file") {
        // Handle the case where the Uri is a direct file path
        path = uri.path
    }

    // If no path is found, return null or handle gracefully
    return path
}


/**
 * Copies the content from a Uri to a file in the cache directory and returns the file path.
 */
fun copyUriToFile(context: Context, uri: Uri): String? {
    return try {
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        val file = File(context.cacheDir, "image_${System.currentTimeMillis()}.png")
        val outputStream = FileOutputStream(file)
        inputStream?.copyTo(outputStream)
        inputStream?.close()
        outputStream.close()
        file.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/**
 * Saves a Bitmap to a file and returns the file path.
 */
fun saveBitmapToFile(context: Context, bitmap: Bitmap?, fileName: String = "image.png"): String? {
    if (bitmap == null) return null
    return try {
        val file = File(context.cacheDir, fileName)
        val outputStream = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        outputStream.flush()
        outputStream.close()
        file.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun copyAssetsIfNotExist(context: Context) {
    val assetsToCopy = listOf(
        "model/phonelm_vocab.mllm",
        "model/qwen2.5_vocab.mllm",
        "model/smollm_vocab.mllm",
        "model/phi3v_vocab.mllm",
        "model/qwen2.5_merges.txt",
        "model/phonelm_merges.txt",
        "model/smollm_merges.txt",
    )

    val destinationDir = File("/sdcard/Download/model")
    if (!destinationDir.exists()) {
        destinationDir.mkdirs()
    }

    assetsToCopy.forEach { asset ->
        val destinationFile = File(destinationDir, asset.substringAfterLast("/"))
        if (!destinationFile.exists()) {
            context.assets.open(asset).use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    copyStream(inputStream, outputStream)
                }
            }
        }
    }
}

fun copyStream(input: InputStream, output: FileOutputStream) {
    val buffer = ByteArray(1024)
    var bytesRead: Int
    while (input.read(buffer).also { bytesRead = it } != -1) {
        output.write(buffer, 0, bytesRead)
    }
}


// Function to download a file from URL
//suspend fun downloadFileFromUrl(context: Context, url: String, destinationFile: File) {
//    try {
//        // Perform the actual download in the background
//        withContext(Dispatchers.IO) {
//            val inputStream: InputStream = URL(url).openStream()
//            FileOutputStream(destinationFile).use { outputStream ->
//                copyStream(inputStream, outputStream)
//            }
//        }
//    } catch (e: Exception) {
//        e.printStackTrace() // Handle any errors during the download process
//    }
//}
