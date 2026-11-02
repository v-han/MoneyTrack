package com.moneytrack.app.ui

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.core.widget.doAfterTextChanged
import com.moneytrack.app.R
import com.moneytrack.app.databinding.ActivityAppPickerBinding
import com.moneytrack.app.databinding.ItemInstalledAppBinding
import java.text.Collator

/** 只列出系统允许查询的带启动入口应用，按名称或包名搜索后进入规则编辑。 */
class AppPickerActivity : BaseActivity() {
    private lateinit var binding: ActivityAppPickerBinding
    private var apps = emptyList<InstalledApp>()
    private var visibleApps = emptyList<InstalledApp>()
    private val adapter = AppAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fitSystemBars(binding.root)
        binding.backButton.setOnClickListener { finish() }
        binding.apps.adapter = adapter
        binding.apps.setOnItemClickListener { _, _, position, _ ->
            val selected = visibleApps[position]
            startActivity(Intent(this, RuleEditorActivity::class.java)
                .putExtra(RuleEditorActivity.EXTRA_PACKAGE, selected.packageName)
                .putExtra(RuleEditorActivity.EXTRA_NAME, selected.name))
            finish()
        }
        binding.search.doAfterTextChanged { filterApps() }
        databaseTask({ runCatching {
            val configured = app.database.rules().map { it.packageName }.toSet()
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val results = packageManager.queryIntentActivities(launcher, 0)
            val collator = Collator.getInstance()
            results.distinctBy { it.activityInfo.packageName }.filter { it.activityInfo.packageName != packageName }
                .map { info ->
                    InstalledApp(info.activityInfo.packageName, runCatching { info.loadLabel(packageManager).toString() }.getOrDefault(info.activityInfo.packageName),
                        runCatching { info.loadIcon(packageManager) }.getOrElse { packageManager.defaultActivityIcon },
                        info.activityInfo.packageName in configured)
                }.sortedWith { first, second -> collator.compare(first.name, second.name) }
        } }) { result ->
            binding.loading.visibility = View.GONE
            result.onSuccess { loaded -> apps = loaded; filterApps() }
                .onFailure { binding.empty.visibility = View.VISIBLE; binding.empty.setText(R.string.app_load_failed) }
        }
    }

    private fun filterApps() {
        val query = binding.search.text.toString().trim()
        visibleApps = apps.filter { it.name.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
        binding.resultCount.text = getString(R.string.app_search_count, visibleApps.size)
        binding.empty.visibility = if (visibleApps.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private data class InstalledApp(val packageName: String, val name: String, val icon: Drawable, val configured: Boolean)

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = visibleApps.size
        override fun getItem(position: Int) = visibleApps[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = if (convertView == null) ItemInstalledAppBinding.inflate(layoutInflater, parent, false)
                .also { it.root.tag = it } else convertView.tag as ItemInstalledAppBinding
            val item = getItem(position)
            row.icon.setImageDrawable(item.icon)
            row.appName.text = item.name
            row.packageLabel.text = item.packageName
            row.actionLabel.setText(if (item.configured) R.string.app_already_configured else R.string.app_choose_action)
            return row.root
        }
    }
}
