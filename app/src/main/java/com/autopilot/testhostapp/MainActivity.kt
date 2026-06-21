package com.autopilot.testhostapp

import android.os.Bundle
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    companion object {
        // Weak reference so tests can request focus on specific fields by content-desc
        @Volatile var instance: MainActivity? = null
    }

    // State
    private var count = 0
    private var dblCount = 0
    private var quantity = 0
    private var flagOn = false
    private var toggleFlagItem: MenuItem? = null

    // Views
    private lateinit var nameField: EditText
    private lateinit var statusLabel: TextView
    private lateinit var countLabel: TextView
    private lateinit var dblLabel: TextView
    private lateinit var okButton: Button
    private lateinit var dblButton: Button
    private lateinit var flagCheckbox: CheckBox
    private lateinit var searchField: EditText
    private lateinit var slider: SeekBar
    private lateinit var sliderValueLabel: TextView
    private lateinit var rightClickTarget: View
    private lateinit var modeSegment: RadioGroup
    private lateinit var segmentLabel: TextView
    private lateinit var colorPicker: Spinner
    private lateinit var pickerLabel: TextView
    private lateinit var quantityStepper: LinearLayout
    private lateinit var stepperIncrement: Button
    private lateinit var stepperDecrement: Button
    private lateinit var quantityLabel: TextView
    private lateinit var uploadProgress: ProgressBar
    private lateinit var advanceButton: Button
    private lateinit var notesArea: EditText
    private lateinit var termsLink: TextView
    private lateinit var fileTable: RecyclerView
    private lateinit var tableSelLabel: TextView
    private lateinit var alertButton: Button
    private lateinit var lockedButton: Button
    private lateinit var disabledLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        setContentView(R.layout.activity_main)

        bindViews()
        setupNameField()
        setupOkButton()
        setupDblButton()
        setupFlagCheckbox()
        setupSlider()
        setupRightClickTarget()
        setupModeSegment()
        setupColorPicker()
        setupQuantityStepper()
        setupUploadProgress()
        setupTermsLink()
        setupFileTable()
        setupAlertButton()

        // Element 9: searchField requests focus on activity start
        searchField.requestFocus()
    }

    // Called from instrumented tests to scroll the inner scrollView to its bottom.
    fun scrollInnerScrollViewToEnd() {
        val sv = findViewById<android.widget.ScrollView>(R.id.scrollView)
        sv?.fullScroll(android.view.View.FOCUS_DOWN)
    }

    // Called from instrumented tests to simulate a double-tap on dblButton.
    fun simulateDoubleTap(contentDesc: String) {
        if (contentDesc == "dblButton") {
            dblCount++
            dblLabel.text = "dbl: $dblCount"
        }
    }

    // Called from instrumented tests to move keyboard focus to a specific field.
    fun requestFocusOnField(contentDesc: String) {
        val root = window.decorView
        val view = findViewByDesc(root, contentDesc)
        view?.requestFocus()
    }

    private fun findViewByDesc(view: View, desc: String): View? {
        if (view.contentDescription == desc) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findViewByDesc(view.getChildAt(i), desc)
                if (found != null) return found
            }
        }
        return null
    }

    private fun bindViews() {
        nameField = findViewById(R.id.nameField)
        statusLabel = findViewById(R.id.statusLabel)
        countLabel = findViewById(R.id.countLabel)
        dblLabel = findViewById(R.id.dblLabel)
        okButton = findViewById(R.id.okButton)
        dblButton = findViewById(R.id.dblButton)
        flagCheckbox = findViewById(R.id.flagCheckbox)
        searchField = findViewById(R.id.searchField)
        slider = findViewById(R.id.slider)
        sliderValueLabel = findViewById(R.id.sliderValueLabel)
        rightClickTarget = findViewById(R.id.rightClickTarget)
        modeSegment = findViewById(R.id.modeSegment)
        segmentLabel = findViewById(R.id.segmentLabel)
        colorPicker = findViewById(R.id.colorPicker)
        pickerLabel = findViewById(R.id.pickerLabel)
        quantityStepper = findViewById(R.id.quantityStepper)
        stepperIncrement = findViewById(R.id.stepperIncrement)
        stepperDecrement = findViewById(R.id.stepperDecrement)
        quantityLabel = findViewById(R.id.quantityLabel)
        uploadProgress = findViewById(R.id.uploadProgress)
        advanceButton = findViewById(R.id.advanceButton)
        notesArea = findViewById(R.id.notesArea)
        termsLink = findViewById(R.id.termsLink)
        fileTable = findViewById(R.id.fileTable)
        tableSelLabel = findViewById(R.id.tableSelLabel)
        alertButton = findViewById(R.id.alertButton)
        lockedButton = findViewById(R.id.lockedButton)
        disabledLabel = findViewById(R.id.disabledLabel)
    }

    // ── Element 1: nameField → statusLabel ──────────────────────────────────
    private fun setupNameField() {
        nameField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                statusLabel.text = "status: ${s.toString()}"
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    // ── Element 5: okButton → countLabel ────────────────────────────────────
    private fun setupOkButton() {
        okButton.setOnClickListener {
            count++
            countLabel.text = "count: $count"
        }
    }

    // ── Element 6: dblButton → dblLabel (double-tap) ────────────────────────
    private var lastDblTapTime = 0L
    private fun setupDblButton() {
        dblButton.setOnClickListener {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastDblTapTime <= 400L) {
                dblCount++
                dblLabel.text = "dbl: $dblCount"
                lastDblTapTime = 0L // reset so triple-click doesn't double-fire
            } else {
                lastDblTapTime = now
            }
        }
    }

    // ── Element 7: flagCheckbox → statusLabel ───────────────────────────────
    private fun setupFlagCheckbox() {
        flagCheckbox.setOnCheckedChangeListener { _, isChecked ->
            statusLabel.text = "status: flag=${isChecked}"
        }
    }

    // ── Element 12: slider → sliderValueLabel ───────────────────────────────
    private fun setupSlider() {
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sliderValueLabel.text = "slider: $progress"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ── Element 14: rightClickTarget → PopupMenu with ContextAction ─────────
    private fun setupRightClickTarget() {
        rightClickTarget.setOnLongClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 0, 0, "ContextAction")
            popup.setOnMenuItemClickListener {
                statusLabel.text = "status: context-tapped"
                true
            }
            popup.show()
            true
        }
    }

    // ── Element 17: modeSegment → segmentLabel ──────────────────────────────
    private fun setupModeSegment() {
        modeSegment.setOnCheckedChangeListener { group, checkedId ->
            val index = when (checkedId) {
                R.id.radioAlpha -> 0
                R.id.radioBeta -> 1
                R.id.radioGamma -> 2
                else -> 0
            }
            segmentLabel.text = "segment: $index"
        }
    }

    // ── Element 19: colorPicker → pickerLabel ───────────────────────────────
    private fun setupColorPicker() {
        val colors = listOf("Red", "Green", "Blue")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, colors)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        colorPicker.adapter = adapter
        colorPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                pickerLabel.text = "pick: ${colors[position]}"
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ── Element 21: quantityStepper ─────────────────────────────────────────
    private fun setupQuantityStepper() {
        stepperIncrement.setOnClickListener {
            if (quantity < 10) {
                quantity++
                quantityLabel.text = "qty: $quantity"
            }
        }
        stepperDecrement.setOnClickListener {
            if (quantity > 0) {
                quantity--
                quantityLabel.text = "qty: $quantity"
            }
        }
    }

    // ── Element 23: uploadProgress / 24: advanceButton ──────────────────────
    private lateinit var progressValueLabel: TextView

    private fun setupUploadProgress() {
        progressValueLabel = findViewById(R.id.progressValueLabel)
        progressValueLabel.text = "0.5"

        advanceButton.setOnClickListener {
            uploadProgress.progress = 100
            progressValueLabel.text = "1.0"
        }
    }

    // ── Element 26: termsLink ───────────────────────────────────────────────
    private fun setupTermsLink() {
        termsLink.setOnClickListener {
            statusLabel.text = "status: link-tapped"
        }
    }

    // ── Element 27: fileTable ───────────────────────────────────────────────
    private fun setupFileTable() {
        val files = listOf("document.pdf", "photo.jpg", "notes.txt")
        fileTable.layoutManager = LinearLayoutManager(this)
        fileTable.adapter = FileTableAdapter(files) { filename ->
            tableSelLabel.text = "table-sel: $filename"
        }
    }

    // ── Element 32: alertButton → AlertDialog ───────────────────────────────
    private fun setupAlertButton() {
        alertButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Are you sure?")
                .setPositiveButton("Confirm") { dialog, _ ->
                    statusLabel.text = "status: alert-confirmed"
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    statusLabel.text = "status: alert-cancelled"
                    dialog.dismiss()
                }
                .show()
        }
    }

    // ── Element 16: toggleFlag in options menu ───────────────────────────────
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        toggleFlagItem = menu.findItem(R.id.toggleFlag)
        toggleFlagItem?.isChecked = flagOn
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.toggleFlag -> {
                flagOn = !flagOn
                item.isChecked = flagOn
                statusLabel.text = "status: flag=${flagOn}"
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
