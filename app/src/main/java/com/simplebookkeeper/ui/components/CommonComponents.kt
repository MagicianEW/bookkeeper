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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplebookkeeper.data.model.Transaction
import com.simplebookkeeper.data.model.Category
import com.simplebookkeeper.data.model.TransactionType
import com.simplebookkeeper.ui.theme.ExpenseRed
import com.simplebookkeeper.ui.theme.IncomeGreen
import com.simplebookkeeper.ui.theme.SavingBlue
import com.simplebookkeeper.ui.theme.WithdrawOrange
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun TransactionItem(
    transaction: Transaction,
    category: Category?,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null
) {
    val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())
    val amountColor = when (transaction.type) {
        TransactionType.INCOME -> IncomeGreen
        TransactionType.EXPENSE -> ExpenseRed
        TransactionType.SAVING -> SavingBlue
        TransactionType.WITHDRAW -> WithdrawOrange
    }
    val amountPrefix = when (transaction.type) {
        TransactionType.INCOME -> "+"
        TransactionType.WITHDRAW -> "+"
        else -> "-"
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
                        color = when (transaction.type) {
                            TransactionType.INCOME -> IncomeGreen.copy(alpha = 0.15f)
                            TransactionType.EXPENSE -> ExpenseRed.copy(alpha = 0.15f)
                            TransactionType.SAVING -> SavingBlue.copy(alpha = 0.15f)
                            TransactionType.WITHDRAW -> WithdrawOrange.copy(alpha = 0.15f)
                        },
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
                    text = category?.name ?: "未知分类",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
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
                    text = "$amountPrefix¥%.2f".format(transaction.amount),
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
    income: Double,
    expense: Double,
    savings: Double = 0.0,
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SummaryItem("收入", income, IncomeGreen)
                VerticalDivider(
                    modifier = Modifier.height(50.dp),
                    color = Color.White.copy(alpha = 0.3f)
                )
                SummaryItem("支出", expense, ExpenseRed)
                VerticalDivider(
                    modifier = Modifier.height(50.dp),
                    color = Color.White.copy(alpha = 0.3f)
                )
                SummaryItem("结余", income - expense, Color.White)
            }
            if (savings > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    SummaryItem("存款", savings, SavingBlue)
                }
            }
        }
    }
}

@Composable
fun SummaryItem(label: String, amount: Double, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "¥%.2f".format(amount),
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
    }
}
