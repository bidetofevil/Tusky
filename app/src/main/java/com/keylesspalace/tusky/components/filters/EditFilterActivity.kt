package com.keylesspalace.tusky.components.filters

import android.content.Context
import android.content.DialogInterface.BUTTON_POSITIVE
import android.os.Bundle
import android.view.WindowManager
import android.widget.AdapterView
import androidx.activity.viewModels
import androidx.core.view.size
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import at.connyduck.calladapter.networkresult.fold
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.keylesspalace.tusky.BaseActivity
import com.keylesspalace.tusky.R
import com.keylesspalace.tusky.appstore.EventHub
import com.keylesspalace.tusky.appstore.FilterUpdatedEvent
import com.keylesspalace.tusky.databinding.ActivityEditFilterBinding
import com.keylesspalace.tusky.databinding.DialogFilterBinding
import com.keylesspalace.tusky.entity.Filter
import com.keylesspalace.tusky.entity.FilterKeyword
import com.keylesspalace.tusky.network.MastodonApi
import com.keylesspalace.tusky.util.getParcelableExtraCompat
import com.keylesspalace.tusky.util.isHttpNotFound
import com.keylesspalace.tusky.util.viewBinding
import com.keylesspalace.tusky.util.visible
import dagger.hilt.android.AndroidEntryPoint
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class EditFilterActivity : BaseActivity() {
    @Inject
    lateinit var api: MastodonApi

    @Inject
    lateinit var eventHub: EventHub

    private val binding by viewBinding(ActivityEditFilterBinding::inflate)
    private val viewModel: EditFilterViewModel by viewModels()

    private lateinit var filter: Filter
    private var originalFilter: Filter? = null
    private lateinit var contextSwitches: Map<MaterialSwitch, Filter.Kind>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        originalFilter = intent.getParcelableExtraCompat(FILTER_TO_EDIT)
        filter = originalFilter ?: Filter("", "", listOf(), null, Filter.Action.WARN.action, listOf())
        binding.apply {
            contextSwitches = mapOf(
                filterContextHome to Filter.Kind.HOME,
                filterContextNotifications to Filter.Kind.NOTIFICATIONS,
                filterContextPublic to Filter.Kind.PUBLIC,
                filterContextThread to Filter.Kind.THREAD,
                filterContextAccount to Filter.Kind.ACCOUNT
            )
        }

        setContentView(binding.root)
        setSupportActionBar(binding.includedToolbar.toolbar)
        supportActionBar?.run {
            // Back button
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        setTitle(
            if (originalFilter == null) {
                R.string.filter_addition_title
            } else {
                R.string.filter_edit_title
            }
        )

        binding.actionChip.setOnClickListener { showAddKeywordDialog() }
        binding.filterSaveButton.setOnClickListener { saveChanges() }
        binding.filterDeleteButton.setOnClickListener {
            lifecycleScope.launch {
                if (showDeleteFilterDialog(filter.title) == BUTTON_POSITIVE) deleteFilter()
            }
        }
        binding.filterDeleteButton.visible(originalFilter != null)

        for (switch in contextSwitches.keys) {
            switch.setOnCheckedChangeListener { _, isChecked ->
                val context = contextSwitches[switch]!!
                if (isChecked) {
                    viewModel.addContext(context)
                } else {
                    viewModel.removeContext(context)
                }
                validateSaveButton()
            }
        }
        binding.filterTitle.doAfterTextChanged { editable ->
            viewModel.setTitle(editable.toString())
            validateSaveButton()
        }
        binding.filterActionWarn.setOnCheckedChangeListener { _, checked ->
            viewModel.setAction(
                if (checked) {
                    Filter.Action.WARN
                } else {
                    Filter.Action.HIDE
                }
            )
        }
        binding.filterDurationDropDown.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            viewModel.setDuration(
                if (originalFilter?.expiresAt == null) {
                    position
                } else {
                    position - 1
                }
            )
        }
        validateSaveButton()

        if (originalFilter == null) {
            binding.filterActionWarn.isChecked = true
        } else {
            loadFilter()
        }
        observeModel()
    }

    private fun observeModel() {
        lifecycleScope.launch {
            viewModel.title.collect { title ->
                if (title != binding.filterTitle.text.toString()) {
                    // We also get this callback when typing in the field,
                    // which messes with the cursor focus
                    binding.filterTitle.setText(title)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.keywords.collect { keywords ->
                updateKeywords(keywords)
            }
        }
        lifecycleScope.launch {
            viewModel.contexts.collect { contexts ->
                for (entry in contextSwitches) {
                    entry.key.isChecked = contexts.contains(entry.value)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.action.collect { action ->
                when (action) {
                    Filter.Action.HIDE -> binding.filterActionHide.isChecked = true
                    else -> binding.filterActionWarn.isChecked = true
                }
            }
        }
    }

    // Populate the UI from the filter's members
    private fun loadFilter() {
        viewModel.load(filter)
        val durationNames = if (filter.expiresAt != null) {
            arrayOf(getString(R.string.duration_no_change)) + resources.getStringArray(R.array.filter_duration_names)
        } else {
            resources.getStringArray(R.array.filter_duration_names)
        }
        binding.filterDurationDropDown.setSimpleItems(durationNames)
        binding.filterDurationDropDown.setText(durationNames[0], false)
    }

    private fun updateKeywords(newKeywords: List<FilterKeyword>) {
        newKeywords.forEachIndexed { index, filterKeyword ->
            val chip = binding.keywordChips.getChildAt(index).takeUnless {
                it.id == R.id.actionChip
            } as Chip? ?: Chip(this).apply {
                setCloseIconResource(R.drawable.ic_cancel_24dp)
                isCheckable = false
                binding.keywordChips.addView(this, binding.keywordChips.size - 1)
            }

            chip.text = if (filterKeyword.wholeWord) {
                binding.root.context.getString(
                    R.string.filter_keyword_display_format,
                    filterKeyword.keyword
                )
            } else {
                filterKeyword.keyword
            }
            chip.isCloseIconVisible = true
            chip.setOnClickListener {
                showEditKeywordDialog(newKeywords[index])
            }
            chip.setOnCloseIconClickListener {
                viewModel.deleteKeyword(newKeywords[index])
            }
        }

        while (binding.keywordChips.size - 1 > newKeywords.size) {
            binding.keywordChips.removeViewAt(newKeywords.size)
        }

        filter = filter.copy(keywords = newKeywords)
        validateSaveButton()
    }

    private fun showAddKeywordDialog() {
        val binding = DialogFilterBinding.inflate(layoutInflater)
        binding.phraseWholeWord.isChecked = true
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.filter_keyword_addition_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.addKeyword(
                    FilterKeyword(
                        "",
                        binding.phraseEditText.text.toString(),
                        binding.phraseWholeWord.isChecked
                    )
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        val editText = binding.phraseEditText
        editText.requestFocus()
        editText.setSelection(editText.length())
    }

    private fun showEditKeywordDialog(keyword: FilterKeyword) {
        val binding = DialogFilterBinding.inflate(layoutInflater)
        binding.phraseEditText.setText(keyword.keyword)
        binding.phraseWholeWord.isChecked = keyword.wholeWord

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.filter_edit_keyword_title)
            .setView(binding.root)
            .setPositiveButton(R.string.filter_dialog_update_button) { _, _ ->
                viewModel.modifyKeyword(
                    keyword,
                    keyword.copy(
                        keyword = binding.phraseEditText.text.toString(),
                        wholeWord = binding.phraseWholeWord.isChecked
                    )
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        val editText = binding.phraseEditText
        editText.requestFocus()
        editText.setSelection(editText.length())
    }

    private fun validateSaveButton() {
        binding.filterSaveButton.isEnabled = viewModel.validate()
    }

    private fun saveChanges() {
        // TODO use a progress bar here (see EditProfileActivity/activity_edit_profile.xml for example)?

        lifecycleScope.launch {
            if (viewModel.saveChanges(this@EditFilterActivity)) {
                finish()
                // Possibly affected contexts: any context affected by the original filter OR any context affected by the updated filter
                val affectedContexts = viewModel.contexts.value.map {
                    it.kind
                }.union(originalFilter?.context ?: listOf()).distinct()
                eventHub.dispatch(FilterUpdatedEvent(affectedContexts))
            } else {
                Snackbar.make(
                    binding.root,
                    getString(R.string.error_deleting_filter, viewModel.title.value),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun deleteFilter() {
        originalFilter?.let { filter ->
            lifecycleScope.launch {
                api.deleteFilter(filter.id).fold(
                    {
                        finish()
                    },
                    { throwable ->
                        if (throwable.isHttpNotFound()) {
                            api.deleteFilterV1(filter.id).fold(
                                {
                                    finish()
                                },
                                {
                                    Snackbar.make(
                                        binding.root,
                                        getString(R.string.error_deleting_filter, filter.title),
                                        Snackbar.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        } else {
                            Snackbar.make(
                                binding.root,
                                getString(R.string.error_deleting_filter, filter.title),
                                Snackbar.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
        }
    }

    companion object {
        const val FILTER_TO_EDIT = "FilterToEdit"

        // Mastodon *stores* the absolute date in the filter,
        // but create/edit take a number of seconds (relative to the time the operation is posted)
        fun getSecondsForDurationIndex(index: Int, context: Context?, default: Date? = null): Int? {
            return when (index) {
                -1 -> if (default == null) {
                    default
                } else {
                    ((default.time - System.currentTimeMillis()) / 1000).toInt()
                }
                0 -> null
                else -> context?.resources?.getIntArray(R.array.filter_duration_values)?.get(index)
            }
        }
    }
}
