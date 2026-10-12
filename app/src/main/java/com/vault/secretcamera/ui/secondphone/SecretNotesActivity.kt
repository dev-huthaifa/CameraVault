package com.vault.secretcamera.ui.secondphone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.databinding.ActivitySecretNotesBinding
import com.vault.secretcamera.databinding.ItemSecretNoteBinding
import com.vault.secretcamera.security.CryptoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SecretNote(
    val id: String,
    var title: String,
    var content: String,
    var updatedAt: Long
)

class SecretNotesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecretNotesBinding
    private val notes = mutableListOf<SecretNote>()
    private lateinit var adapter: NotesAdapter
    private var currentNote: SecretNote? = null

    private val notesFile: File by lazy {
        File(filesDir, "secret_notes.enc")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecretNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as SecretVaultApp
        if (app.securityPreferences.isAntiScreenshotEnabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        setupToolbars()
        setupRecyclerView()
        setupBackPress()
        loadNotes()
    }

    private fun setupToolbars() {
        binding.notesToolbar.setNavigationOnClickListener {
            finish()
        }

        binding.btnAddNote.setOnClickListener {
            openEditor(null)
        }

        binding.editorToolbar.setNavigationOnClickListener {
            closeEditor()
        }

        binding.btnSaveNote.setOnClickListener {
            saveCurrentNote()
        }

        binding.btnDeleteNote.setOnClickListener {
            confirmDeleteNote()
        }
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.layoutNoteEditor.visibility == View.VISIBLE) {
                    closeEditor()
                } else {
                    finish()
                }
            }
        })
    }

    private fun setupRecyclerView() {
        adapter = NotesAdapter(notes) { note ->
            openEditor(note)
        }
        binding.rvNotes.layoutManager = LinearLayoutManager(this)
        binding.rvNotes.adapter = adapter
    }

    private fun openEditor(note: SecretNote?) {
        currentNote = note
        if (note != null) {
            binding.etNoteTitle.setText(note.title)
            binding.etNoteContent.setText(note.content)
            binding.btnDeleteNote.visibility = View.VISIBLE
        } else {
            binding.etNoteTitle.setText("")
            binding.etNoteContent.setText("")
            binding.btnDeleteNote.visibility = View.GONE
        }
        binding.layoutNotesList.visibility = View.GONE
        binding.layoutNoteEditor.visibility = View.VISIBLE
    }

    private fun closeEditor() {
        binding.layoutNoteEditor.visibility = View.GONE
        binding.layoutNotesList.visibility = View.VISIBLE
        currentNote = null
    }

    private fun saveCurrentNote() {
        val title = binding.etNoteTitle.text.toString().trim()
        val content = binding.etNoteContent.text.toString().trim()

        if (title.isEmpty() && content.isEmpty()) {
            Toast.makeText(this, "يرجى كتابة عنوان أو محتوى للملاحظة", Toast.LENGTH_SHORT).show()
            return
        }

        val effectiveTitle = if (title.isEmpty()) "ملاحظة بدون عنوان" else title

        if (currentNote != null) {
            currentNote?.let {
                it.title = effectiveTitle
                it.content = content
                it.updatedAt = System.currentTimeMillis()
            }
        } else {
            val newNote = SecretNote(
                id = UUID.randomUUID().toString(),
                title = effectiveTitle,
                content = content,
                updatedAt = System.currentTimeMillis()
            )
            notes.add(0, newNote)
        }

        persistNotes()
        adapter.notifyDataSetChanged()
        closeEditor()
        updateEmptyState()
        Toast.makeText(this, "تم حفظ الملاحظة وتشفيرها بنجاح 🔒", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeleteNote() {
        val note = currentNote ?: return
        AlertDialog.Builder(this)
            .setTitle("حذف الملاحظة")
            .setMessage("هل أنت متأكد من حذف هذه الملاحظة نهائياً؟")
            .setPositiveButton("حذف") { _, _ ->
                notes.remove(note)
                persistNotes()
                adapter.notifyDataSetChanged()
                closeEditor()
                updateEmptyState()
                Toast.makeText(this, "تم حذف الملاحظة", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun updateEmptyState() {
        binding.layoutEmptyNotes.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadNotes() {
        CoroutineScope(Dispatchers.IO).launch {
            val app = application as SecretVaultApp
            val masterKey = app.securityPreferences.currentMasterKey

            if (!notesFile.exists() || masterKey == null) {
                withContext(Dispatchers.Main) {
                    updateEmptyState()
                }
                return@launch
            }

            try {
                val decryptedBytes = CryptoManager.decryptFileToBytes(notesFile, masterKey)
                val jsonStr = String(decryptedBytes, Charsets.UTF_8)
                val jsonArray = JSONArray(jsonStr)

                val list = mutableListOf<SecretNote>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(
                        SecretNote(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            content = obj.getString("content"),
                            updatedAt = obj.getLong("updatedAt")
                        )
                    )
                }

                list.sortByDescending { it.updatedAt }

                withContext(Dispatchers.Main) {
                    notes.clear()
                    notes.addAll(list)
                    adapter.notifyDataSetChanged()
                    updateEmptyState()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    updateEmptyState()
                }
            }
        }
    }

    private fun persistNotes() {
        CoroutineScope(Dispatchers.IO).launch {
            val app = application as SecretVaultApp
            val masterKey = app.securityPreferences.currentMasterKey ?: return@launch

            try {
                val jsonArray = JSONArray()
                for (n in notes) {
                    val obj = JSONObject().apply {
                        put("id", n.id)
                        put("title", n.title)
                        put("content", n.content)
                        put("updatedAt", n.updatedAt)
                    }
                    jsonArray.put(obj)
                }

                val bytes = jsonArray.toString().toByteArray(Charsets.UTF_8)
                ByteArrayInputStream(bytes).use { bais ->
                    CryptoManager.encryptStreamToFile(bais, notesFile, masterKey)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private class NotesAdapter(
        private val items: List<SecretNote>,
        private val onItemClick: (SecretNote) -> Unit
    ) : RecyclerView.Adapter<NotesAdapter.NoteViewHolder>() {

        private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

        inner class NoteViewHolder(val b: ItemSecretNoteBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
            val b = ItemSecretNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return NoteViewHolder(b)
        }

        override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
            val note = items[position]
            holder.b.tvNoteTitle.text = note.title
            holder.b.tvNoteSnippet.text = if (note.content.isNotBlank()) note.content else "(فارغ)"
            holder.b.tvNoteDate.text = dateFormat.format(Date(note.updatedAt))

            holder.itemView.setOnClickListener {
                onItemClick(note)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
