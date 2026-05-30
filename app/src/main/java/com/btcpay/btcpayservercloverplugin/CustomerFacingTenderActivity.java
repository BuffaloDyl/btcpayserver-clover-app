package com.buffalodyl.btcpayservercloverplugin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import com.clover.sdk.v1.Intents;
import com.clover.sdk.v3.payments.api.RequestTipIntentBuilder;
import com.clover.sdk.v3.payments.api.TipSuggestion;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

public class CustomerFacingTenderActivity extends Activity {
    private static final int REQUEST_TIP = 1001;
    private static final String TAG = "BTCPayTenderCustomer";
    private TextView textAmount, textStatus, textSubtitle, textSubtotal, textTip;
    private ImageView imageQr;
    private Button btnCancel;

    private BTCPayApiClient btcPayClient;
    private String currentInvoiceId;
    private String orderId;
    private String merchantId;
    private String employeeId;
    private Currency currency;
    private long baseAmountCents;
    private long tipAmountCents;
    private long totalAmountCents;
    private boolean polling = false;
    private boolean finalizingPayment = false;

    private final Handler handler = new Handler();
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            checkInvoiceStatus();
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tender_customer);
        setResult(RESULT_CANCELED);
        setSystemUiVisibility();

        textAmount = findViewById(R.id.text_amount);
        textSubtitle = findViewById(R.id.text_subtitle);
        textStatus = findViewById(R.id.text_status);
        textSubtotal = findViewById(R.id.text_subtotal);
        textTip = findViewById(R.id.text_tip);
        imageQr = findViewById(R.id.image_qr);
        btnCancel = findViewById(R.id.btn_cancel);

        baseAmountCents = getIntent().getLongExtra(Intents.EXTRA_AMOUNT, 0);
        orderId = getIntent().getStringExtra(Intents.EXTRA_ORDER_ID);
        merchantId = getIntent().getStringExtra(Intents.EXTRA_MERCHANT_ID);
        employeeId = getIntent().getStringExtra(Intents.EXTRA_EMPLOYEE_ID);
        currency = (Currency) getIntent().getSerializableExtra(Intents.EXTRA_CURRENCY);
        Log.i(TAG, "onCreate amount=" + baseAmountCents
                + " orderId=" + orderId
                + " merchantId=" + merchantId
                + " employeeId=" + employeeId
                + " currency=" + (currency == null ? "null" : currency.getCurrencyCode())
                + " tipFlowMode=" + TipFlowConfig.getActiveTipFlow());
        if (currency == null) {
            textStatus.setText("Could not determine currency. Please try again.");
            Log.w(TAG, "onCreate missing currency; leaving tender in canceled state");
            return;
        }
        if (launchStandalonePos()) {
            return;
        }
        tipAmountCents = 0L;
        totalAmountCents = baseAmountCents;

        updateAmountDisplay();
        textSubtitle.setText("Choose your tip on Clover");
        textStatus.setText("Waiting for tip selection...");

        btcPayClient = new BTCPayApiClient(this);

        if (!btcPayClient.isConfigured()) {
            textStatus.setText("BTCPay not configured.");
            Log.w(TAG, "BTCPay client not configured; leaving tender in canceled state");
            return;
        }

        btnCancel.setOnClickListener(v -> {
            Log.i(TAG, "Cancel clicked currentInvoiceId=" + currentInvoiceId + " polling=" + polling);
            polling = false;
            handler.removeCallbacks(pollRunnable);
            setResult(RESULT_CANCELED);
            finish();
        });

        startTipFlow();
    }

    private boolean launchStandalonePos() {
        if (baseAmountCents <= 0L) {
            return false;
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_POS_HANDOFF, true);
        intent.putExtra(MainActivity.EXTRA_HANDOFF_AMOUNT, baseAmountCents);
        intent.putExtra(MainActivity.EXTRA_HANDOFF_ORDER_ID, orderId);
        intent.putExtra(MainActivity.EXTRA_HANDOFF_MERCHANT_ID, merchantId);
        intent.putExtra(MainActivity.EXTRA_HANDOFF_EMPLOYEE_ID, employeeId);
        intent.putExtra(MainActivity.EXTRA_HANDOFF_CURRENCY, currency.getCurrencyCode());
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Log.i(TAG, "Launching standalone POS handoff amount=" + baseAmountCents + " orderId=" + orderId);
        startActivity(intent);
        setResult(RESULT_CANCELED);
        finish();
        return true;
    }

    public void setSystemUiVisibility() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LOW_PROFILE
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void startTipFlow() {
        Log.i(TAG, "startTipFlow mode=" + TipFlowConfig.getActiveTipFlow());
        if (TipFlowConfig.getActiveTipFlow() == TipFlowMode.CLOVER_NATIVE) {
            openNativeTipFlow();
            return;
        }
        openLocalTipDialog();
    }

    private void openNativeTipFlow() {
        textStatus.setText("Opening Clover tip selection...");
        Log.i(TAG, "Opening Clover native tip flow baseAmount=" + baseAmountCents);
        List<TipSuggestion> suggestions = new ArrayList<>();
        suggestions.add(TipSuggestion.Amount("No tip", 0L));
        suggestions.add(TipSuggestion.Percentage("15%", 15L));
        suggestions.add(TipSuggestion.Percentage("20%", 20L));
        suggestions.add(TipSuggestion.Percentage("25%", 25L));
        Intent intent = new RequestTipIntentBuilder(baseAmountCents)
                .tipSuggestions(suggestions)
                .build(this);
        startActivityForResult(intent, REQUEST_TIP);
    }

    private void openLocalTipDialog() {
        textStatus.setText("Select tip to continue...");
        Log.i(TAG, "Opening local tip dialog baseAmount=" + baseAmountCents);
        long fifteenPercent = calculatePercentageTip(15);
        long twentyPercent = calculatePercentageTip(20);
        long twentyFivePercent = calculatePercentageTip(25);
        CharSequence[] options = new CharSequence[] {
                "No tip",
                "15% (" + formatAmount(currency, fifteenPercent) + ")",
                "20% (" + formatAmount(currency, twentyPercent) + ")",
                "25% (" + formatAmount(currency, twentyFivePercent) + ")",
                "Custom amount"
        };

        new AlertDialog.Builder(this)
                .setTitle("Select tip")
                .setCancelable(false)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            applyTipSelection(0L);
                            break;
                        case 1:
                            applyTipSelection(fifteenPercent);
                            break;
                        case 2:
                            applyTipSelection(twentyPercent);
                            break;
                        case 3:
                            applyTipSelection(twentyFivePercent);
                            break;
                        default:
                            showCustomTipDialog();
                            break;
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    Log.i(TAG, "Local tip dialog canceled");
                    setResult(RESULT_CANCELED);
                    finish();
                })
                .show();
    }

    private void createInvoice() {
        textSubtitle.setText("Scan with your phone to pay");
        textStatus.setText("Creating invoice...");
        Log.i(TAG, "createInvoice baseAmount=" + baseAmountCents
                + " tipAmount=" + tipAmountCents
                + " totalAmount=" + totalAmountCents
                + " orderId=" + orderId);
        new Thread(() -> {
            try {
                BTCPayApiClient.InvoiceResult invoice = btcPayClient.createInvoice(
                        totalAmountCents,
                        currency.getCurrencyCode(),
                        orderId,
                        merchantId,
                        employeeId,
                        baseAmountCents,
                        tipAmountCents);
                currentInvoiceId = invoice.invoiceId;
                Log.i(TAG, "Invoice created invoiceId=" + currentInvoiceId
                        + " paymentMethod=" + invoice.paymentMethodId);

                int sizePx = (int) (getResources().getDisplayMetrics().density * 250);
                Bitmap qr = QRCodeHelper.generateQRCode(invoice.paymentPayload, sizePx);

                runOnUiThread(() -> {
                    textStatus.setText("Scan QR to pay using " + formatPaymentMethod(invoice.paymentMethodId));
                    if (qr != null) {
                        imageQr.setImageBitmap(qr);
                        imageQr.setVisibility(View.VISIBLE);
                    }
                    startPolling();
                });
            } catch (Exception e) {
                Log.e(TAG, "Invoice creation failed", e);
                runOnUiThread(() -> textStatus.setText("Error: " + e.getMessage()));
            }
        }).start();
    }

    private void updateAmountDisplay() {
        textAmount.setText(formatAmount(currency, totalAmountCents));
        updateBreakdownDisplay();
    }

    private void updateBreakdownDisplay() {
        textSubtotal.setText("Subtotal: " + formatAmount(currency, baseAmountCents));
        textTip.setText("Tip: " + formatAmount(currency, tipAmountCents));
    }

    private long calculatePercentageTip(int percent) {
        return Math.round(baseAmountCents * (percent / 100.0));
    }

    private void applyTipSelection(long selectedTipCents) {
        tipAmountCents = Math.max(selectedTipCents, 0L);
        totalAmountCents = baseAmountCents + tipAmountCents;
        Log.i(TAG, "applyTipSelection tipAmount=" + tipAmountCents + " totalAmount=" + totalAmountCents);
        updateAmountDisplay();
        createInvoice();
    }

    private void showCustomTipDialog() {
        EditText input = new EditText(this);
        input.setHint("0.00");
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Custom tip")
                .setMessage("Enter tip amount in " + currency.getCurrencyCode())
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Apply", null)
                .setNegativeButton("Back", (d, which) -> openLocalTipDialog())
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                BigDecimal entered = new BigDecimal(input.getText().toString().trim());
                long customTipCents = entered
                        .movePointRight(2)
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact();
                dialog.dismiss();
                Log.i(TAG, "Custom tip entered tipAmount=" + customTipCents);
                applyTipSelection(customTipCents);
            } catch (Exception e) {
                input.setError("Enter a valid amount");
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.i(TAG, "onActivityResult requestCode=" + requestCode
                + " resultCode=" + resultCode
                + " hasData=" + (data != null));
        if (requestCode != REQUEST_TIP) {
            return;
        }

        if (resultCode == RESULT_OK && data != null) {
            long selectedTip = data.getLongExtra(RequestTipIntentBuilder.Response.TIP_AMOUNT, 0L);
            Log.i(TAG, "Native tip flow returned tipAmount=" + selectedTip);
            applyTipSelection(selectedTip);
            return;
        }

        Log.w(TAG, "Native tip flow canceled or failed; returning canceled");
        setResult(RESULT_CANCELED);
        finish();
    }

    private void startPolling() {
        polling = true;
        Log.i(TAG, "startPolling invoiceId=" + currentInvoiceId);
        handler.post(pollRunnable);
    }

    private void checkInvoiceStatus() {
        if (currentInvoiceId == null) return;
        new Thread(() -> {
            try {
                String status = btcPayClient.getInvoiceStatus(currentInvoiceId);
                Log.d(TAG, "Invoice status invoiceId=" + currentInvoiceId + " status=" + status);
                runOnUiThread(() -> handleStatus(status));
            } catch (Exception e) {
                Log.w(TAG, "Invoice status check failed invoiceId=" + currentInvoiceId, e);
            }
        }).start();
    }

    private void handleStatus(String status) {
        Log.i(TAG, "handleStatus status=" + status
                + " finalizingPayment=" + finalizingPayment
                + " polling=" + polling
                + " invoiceId=" + currentInvoiceId);
        switch (status) {
            case "Settled":
            case "Processing":
                if (finalizingPayment) {
                    Log.i(TAG, "Already finalizing; ignoring duplicate status=" + status);
                    return;
                }
                finalizingPayment = true;
                polling = false;
                handler.removeCallbacks(pollRunnable);
                textStatus.setText("Payment received. Finalizing Clover sale...");
                imageQr.setVisibility(View.GONE);
                Log.i(TAG, "Spawning finalizeCloverSale thread amount=" + totalAmountCents
                        + " tipAmount=" + tipAmountCents
                        + " clientId=" + currentInvoiceId);
                new Thread(() -> finalizeCloverSale()).start();
                break;
            case "Expired":
            case "Invalid":
                polling = false;
                handler.removeCallbacks(pollRunnable);
                textStatus.setText("Invoice expired. Tap Cancel.");
                Log.w(TAG, "Invoice ended without payment status=" + status + " invoiceId=" + currentInvoiceId);
                break;
        }
    }

    private String formatAmount(Currency currency, long amountCents) {
        if (currency == null) return String.format("%.2f", amountCents / 100.0);
        return currency.getSymbol() + String.format("%.2f", amountCents / 100.0);
    }

    private String formatPaymentMethod(String paymentMethodId) {
        if ("BTC-LN".equals(paymentMethodId)) return "Lightning";
        if ("BTC-CHAIN".equals(paymentMethodId)) return "Bitcoin";
        if ("BTC-LNURL".equals(paymentMethodId)) return "LNURL";
        return "BTCPay Server";
    }

    private void finalizeCloverSale() {
        runOnUiThread(() -> {
            textStatus.setText("Payment recorded in Clover!");
            Intent data = new Intent();
            data.putExtra(Intents.EXTRA_AMOUNT, totalAmountCents);
            data.putExtra(Intents.EXTRA_TIP_AMOUNT, tipAmountCents);
            data.putExtra(Intents.EXTRA_CLIENT_ID, currentInvoiceId);
            Log.i(TAG, "finalizeCloverSale setting RESULT_OK amount=" + totalAmountCents
                    + " tipAmount=" + tipAmountCents
                    + " clientId=" + currentInvoiceId);
            setResult(RESULT_OK, data);
            Log.i(TAG, "finalizeCloverSale finishing activity");
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "onDestroy invoiceId=" + currentInvoiceId
                + " polling=" + polling
                + " finalizingPayment=" + finalizingPayment
                + " isFinishing=" + isFinishing());
        polling = false;
        handler.removeCallbacks(pollRunnable);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Block back press on customer screen
    }
}
