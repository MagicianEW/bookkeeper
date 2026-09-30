package com.simplebookkeeper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplebookkeeper.R
import com.simplebookkeeper.data.model.Transaction
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.PaymentMethod
import com.simplebookkeeper.data.model.TransactionType
import com.simplebookkeeper.ui.theme.ExpenseRed
import com.simplebookkeeper.ui.theme.IncomeGreen
import java.text.SimpleDateFormat
import java.util.Locale

private val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())

@Composable
fun TransactionItem(
    transaction: Transaction,
    category: Category?,
    /** 付款方式/收款方式展示名。自定义方式优先，否则为 null（由标准方式本地化） */
    paymentMethodLabel: String? = null,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null
) {
    val amountColor = if (transaction.type == TransactionType.INCOME) IncomeGreen else ExpenseRed
    val amountPrefix = if (transaction.type == TransactionType.INCOME) "+" else "-"
    // 付款方式：v0.4.6 起该字段一直在库里，但此前界面从不展示
    val methodText = paymentMethodLabel ?: when (transaction.paymentMethod) {
        PaymentMethod.CASH -> stringResource(R.string.cash)
        PaymentMethod.WECHAT -> stringResource(R.string.wechat)
        PaymentMethod.ALIPAY -> stringResource(R.string.alipay)
        PaymentMethod.BANK_CARD -> stringResource(R.string.card)
        PaymentMethod.CREDIT_CARD -> stringResource(R.string.credit_card)
        PaymentMethod.OTHER -> stringResource(R.string.other)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 分类标签
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (transaction.type == TransactionType.INCOME)
                            IncomeGreen.copy(alpha = 0.15f)
                        else
                            ExpenseRed.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = category?.name?.take(1) ?: "?",
                    color = amountColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category?.name ?: stringResource(R.string.unknown_category),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                // 付款方式副标题
                Text(
                    text = stringResource(
                        if (transaction.type == TransactionType.INCOME)
                            R.string.collection_method_short else R.string.payment_method_short,
                        methodText
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (transaction.note.isNotBlank()) {
                    Text(
                        text = transaction.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$amountPrefix¥%.2f".format(transaction.amount / 100.0),
                    color = amountColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = dateFormat.format(transaction.date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
fun SummaryCard(
    income: Long,    // 单位：分
    expense: Long,    // 单位：分
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryItem(stringResource(R.string.income), income, IncomeGreen)
            VerticalDivider(
                modifier = Modifier.height(50.dp),
                color = Color.White.copy(alpha = 0.3f)
            )
            SummaryItem(stringResource(R.string.expense), expense, ExpenseRed)
            VerticalDivider(
                modifier = Modifier.height(50.dp),
                color = Color.White.copy(alpha = 0.3f)
            )
            SummaryItem(stringResource(R.string.balance), income - expense, Color.White)
        }
    }
}

@Composable
fun SummaryItem(label: String, amountInCents: Long, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "¥%.2f".format(amountInCents / 100.0),
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
    }
}
