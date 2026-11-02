package com.moneytrack.app.ui

import android.content.Intent
import android.os.Bundle
import com.moneytrack.app.R
import com.moneytrack.app.databinding.ActivityNotificationConfigurationBinding

/** 通知配置统一入口，分别跳转识别规则、主副联动和配置文件传输。 */
class NotificationConfigurationActivity : BaseActivity() {
    private lateinit var binding: ActivityNotificationConfigurationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationConfigurationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fitSystemBars(binding.root)
        binding.backButton.setOnClickListener { finish() }
        binding.rulesButton.setOnClickListener { startActivity(Intent(this, RulesActivity::class.java)) }
        binding.linksButton.setOnClickListener { startActivity(Intent(this, LinkRulesActivity::class.java)) }
        binding.transferButton.setOnClickListener { startActivity(Intent(this, RuleTransferActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        databaseTask({ app.database.rules().count { it.enabled } to app.database.links().count { it.enabled } }) { (rules, links) ->
            binding.summary.text = getString(R.string.notification_configuration_summary, rules, links)
        }
    }
}
