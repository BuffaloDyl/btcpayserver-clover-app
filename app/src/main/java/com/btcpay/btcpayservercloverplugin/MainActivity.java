package com.buffalodyl.btcpayservercloverplugin;

import android.accounts.Account;
import android.app.Activity;
import android.app.AlertDialog;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.CheckBox;

import com.clover.sdk.util.CloverAccount;
import com.clover.sdk.v1.Intents;
import com.clover.sdk.v1.ResultStatus;
import com.clover.sdk.v1.ServiceConnector;
import com.clover.sdk.v1.tender.Tender;
import com.clover.sdk.v1.tender.TenderConnector;
import com.clover.sdk.v3.employees.AccountRole;
import com.clover.sdk.v3.employees.Employee;
import com.clover.sdk.v3.employees.EmployeeConnector;
import java.util.Currency;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "BTCPayPosMain";
    private static final int ENTRY_MODE_AMOUNT = 0;
    private static final int ENTRY_MODE_TIP_OPTIONS = 1;
    private static final int ENTRY_MODE_TIP_CUSTOM = 2;

    private LinearLayout layoutSetupInfo;
    private LinearLayout layoutEntryState;
    private LinearLayout layoutSaleState;
    private TextView textConfigWarning;
    private TextView textEntryLabel;
    private TextView textEntryContext;
    private TextView textEntryTotalPreview;
    private TextView textAmountDisplay;
    private TextView textEmployee;
    private TextView textConfigStatus;
    private TextView textCloverStatus;
    private TextView textStatus;
    private TextView textSubtotal;
    private TextView textTip;
    private TextView textTotal;
    private TextView textSuccessCheckmark;
    private ImageView imageQr;
    private GridLayout layoutTipPresets;
    private GridLayout layoutKeypad;
    private Button btnCharge;
    private Button btnSecondaryAction;
    private ImageButton btnSettings;
    private Button btnCancelSale;
    private Button btnNewSale;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Currency currency = resolveCurrency();

    private TenderConnector tenderConnector;
    private boolean tenderRegistrationRequested = false;
    private boolean polling = false;
    private boolean saleInProgress = false;
    private boolean finalizingSale = false;

    private String activeEmployeeId;
    private String activeEmployeeName = "Unknown";
    private boolean canManageSettings = false;

    private String currentOrderId;
    private String currentInvoiceId;
    private long currentBaseAmountCents;
    private long currentTipAmountCents;
    private long currentTotalAmountCents;
    private String enteredAmountDigits = "";
    private String enteredTipDigits = "";
    private int entryMode = ENTRY_MODE_AMOUNT;
    private boolean btcpayConfigured = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling || currentInvoiceId == null) {
                return;
            }
            checkInvoiceStatus();
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        layoutSetupInfo = findViewById(R.id.layout_setup_info);
        layoutEntryState = findViewById(R.id.layout_entry_state);
        layoutSaleState = findViewById(R.id.layout_sale_state);
        textConfigWarning = findViewById(R.id.text_config_warning);
        textEntryLabel = findViewById(R.id.text_entry_label);
        textEntryContext = findViewById(R.id.text_entry_context);
        textEntryTotalPreview = findViewById(R.id.text_entry_total_preview);
        textAmountDisplay = findViewById(R.id.text_amount_display);
        textEmployee = findViewById(R.id.text_employee);
        textConfigStatus = findViewById(R.id.text_config_status);
        textCloverStatus = findViewById(R.id.text_clover_status);
        btnSettings = findViewById(R.id.btn_settings);
        btnCharge = findViewById(R.id.btn_charge);
        btnSecondaryAction = findViewById(R.id.btn_secondary_action);
        textStatus = findViewById(R.id.text_status);
        textSubtotal = findViewById(R.id.text_subtotal);
        textTip = findViewById(R.id.text_tip);
        textTotal = findViewById(R.id.text_total);
        textSuccessCheckmark = findViewById(R.id.text_success_checkmark);
        imageQr = findViewById(R.id.image_qr);
        layoutTipPresets = findViewById(R.id.layout_tip_presets);
        layoutKeypad = findViewById(R.id.layout_keypad);
        btnCancelSale = findViewById(R.id.btn_cancel_sale);
        btnNewSale = findViewById(R.id.btn_new_sale);

        btnSettings.setOnClickListener(v -> showSettingsDialog());
        btnCharge.setOnClickListener(v -> beginChargeFlow());
        btnSecondaryAction.setOnClickListener(v -> handleSecondaryAction());
        btnCancelSale.setOnClickListener(v -> cancelActiveSale());
        btnNewSale.setOnClickListener(v -> resetSaleUi());
        bindKeypad();

        updateConfigSummary();
        resetSaleUi();
        refreshEmployeeContext();
    }

    @Override
    protected void onResume() {
        super.onResume();
        connectTenderConnector();
        registerTender();
        if (saleInProgress && currentInvoiceId != null && !polling && !finalizingSale) {
            startPolling();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        disconnectTenderConnector();
        if (polling) {
            polling = false;
            handler.removeCallbacks(pollRunnable);
        }
    }

    private void connectTenderConnector() {
        Account account = CloverAccount.getAccount(this);
        if (account == null) {
            textCloverStatus.setText("No Clover account available.");
            return;
        }
        if (tenderConnector == null) {
            tenderConnector = new TenderConnector(this, account, null);
        }
        tenderConnector.connect();
    }

    private void disconnectTenderConnector() {
        if (tenderConnector != null) {
            tenderConnector.disconnect();
        }
    }

    private void registerTender() {
        if (tenderConnector == null || tenderRegistrationRequested) {
            return;
        }
        tenderRegistrationRequested = true;
        textCloverStatus.setText("Configuring Clover reporting tender...");
        tenderConnector.checkAndCreateTender(
                getString(R.string.tender_name),
                getPackageName(),
                true,
                false,
                new TenderConnector.TenderCallback<Tender>() {
                    @Override
                    public void onServiceSuccess(Tender result, ResultStatus status) {
                        new Thread(() -> {
                            try {
                                new CloverTenderConfigurator(MainActivity.this).ensureSupportsTipping(result);
                                runOnUiThread(() -> textCloverStatus.setText("Clover reporting tender ready."));
                            } catch (Exception e) {
                                Log.e(TAG, "Tender configuration failed", e);
                                runOnUiThread(() -> textCloverStatus.setText("Clover tender configured, tip support update failed."));
                            }
                        }).start();
                    }

                    @Override
                    public void onServiceFailure(ResultStatus status) {
                        tenderRegistrationRequested = false;
                        runOnUiThread(() -> textCloverStatus.setText("Tender registration failed: " + status));
                    }

                    @Override
                    public void onServiceConnectionFailure() {
                        tenderRegistrationRequested = false;
                        runOnUiThread(() -> textCloverStatus.setText("Tender registration connection failure."));
                    }
                }
        );
    }

    private void refreshEmployeeContext() {
        textEmployee.setText("Loading employee context...");
        new Thread(() -> {
            Account account = CloverAccount.getAccount(this);
            if (account == null) {
                runOnUiThread(() -> {
                    activeEmployeeId = null;
                    activeEmployeeName = "Unknown";
                    canManageSettings = false;
                    textEmployee.setText("No Clover employee context");
                    btnSettings.setVisibility(View.GONE);
                });
                return;
            }

            EmployeeConnector employeeConnector = new EmployeeConnector(this, account, null);
            employeeConnector.connect();
            try {
                Employee employee = employeeConnector.getEmployee();
                activeEmployeeId = employee == null ? null : employee.getId();
                activeEmployeeName = employee == null || employee.getName() == null ? "Unknown" : employee.getName();
                canManageSettings = isPrivilegedEmployee(employee);
                String roleLabel = employee == null || employee.getRole() == null
                        ? "Unknown"
                        : employee.getRole().name();
                boolean isOwner = employee != null && Boolean.TRUE.equals(employee.getIsOwner());
                runOnUiThread(() -> {
                    String ownership = isOwner ? "Owner" : roleLabel;
                    textEmployee.setText("Employee: " + activeEmployeeName + " (" + ownership + ")");
                    btnSettings.setVisibility(canManageSettings ? View.VISIBLE : View.GONE);
                });
            } catch (Exception e) {
                Log.w(TAG, "Could not load active employee", e);
                runOnUiThread(() -> {
                    activeEmployeeId = null;
                    activeEmployeeName = "Unknown";
                    canManageSettings = false;
                    textEmployee.setText("Employee access unavailable");
                    btnSettings.setVisibility(View.GONE);
                });
            } finally {
                employeeConnector.disconnect();
            }
        }).start();
    }

    private boolean isPrivilegedEmployee(Employee employee) {
        if (employee == null) {
            return false;
        }
        if (Boolean.TRUE.equals(employee.getIsOwner())) {
            return true;
        }
        AccountRole role = employee.getRole();
        return role == AccountRole.ADMIN || role == AccountRole.MANAGER;
    }

    private void beginChargeFlow() {
        if (saleInProgress) {
            textStatus.setText("Finish or cancel the current sale first.");
            return;
        }

        if (!new BTCPayApiClient(this).isConfigured()) {
            textStatus.setText("BTCPay settings are required before charging.");
            return;
        }

        if (entryMode == ENTRY_MODE_AMOUNT) {
            long baseAmountCents = getCurrentEntryAmountCents();
            if (baseAmountCents <= 0) {
                textEntryContext.setText("Amount must be greater than zero.");
                return;
            }
            if (!BTCPayApiClient.loadConfiguration(this).tippingEnabled) {
                startSale(baseAmountCents, 0L);
                return;
            }
            currentBaseAmountCents = baseAmountCents;
            entryMode = ENTRY_MODE_TIP_OPTIONS;
            enteredTipDigits = "";
            updateEntryUi();
            return;
        }

        if (entryMode == ENTRY_MODE_TIP_OPTIONS) {
            startSale(currentBaseAmountCents, 0L);
            return;
        }

        startSale(currentBaseAmountCents, getCurrentEntryAmountCents());
    }

    private void startSale(long baseAmountCents, long tipAmountCents) {
        saleInProgress = true;
        finalizingSale = false;
        currentBaseAmountCents = baseAmountCents;
        currentTipAmountCents = Math.max(tipAmountCents, 0L);
        currentTotalAmountCents = currentBaseAmountCents + currentTipAmountCents;
        currentOrderId = null;
        currentInvoiceId = null;

        updateBreakdown();
        imageQr.setImageBitmap(null);
        imageQr.setVisibility(View.GONE);
        textSuccessCheckmark.setVisibility(View.GONE);
        showSaleState();
        btnCancelSale.setVisibility(View.VISIBLE);
        btnNewSale.setVisibility(View.GONE);
        textStatus.setText("Creating Clover order...");

        new Thread(() -> {
            try {
                CloverOrderManager orderManager = new CloverOrderManager(this);
                currentOrderId = orderManager.createManualOrder(
                        activeEmployeeId,
                        currentBaseAmountCents,
                        currentTipAmountCents,
                        "Bitcoin Sale");
                Log.i(TAG, "Created Clover order " + currentOrderId);

                BTCPayApiClient client = new BTCPayApiClient(this);
                BTCPayApiClient.InvoiceResult invoice = client.createInvoice(
                        currentTotalAmountCents,
                        currency.getCurrencyCode(),
                        currentOrderId,
                        null,
                        activeEmployeeId,
                        currentBaseAmountCents,
                        currentTipAmountCents);

                currentInvoiceId = invoice.invoiceId;
                Bitmap qrBitmap = QRCodeHelper.generateQRCode(invoice.paymentPayload, qrSizePx());

                runOnUiThread(() -> {
                    textStatus.setText("Scan QR to pay using " + formatPaymentMethod(invoice.paymentMethodId));
                    imageQr.setImageBitmap(qrBitmap);
                    imageQr.setVisibility(View.VISIBLE);
                    startPolling();
                });
            } catch (Exception e) {
                Log.e(TAG, "Sale start failed", e);
                runOnUiThread(() -> handleSaleFailure("Could not start sale: " + e.getMessage()));
            }
        }).start();
    }

    private void startPolling() {
        if (currentInvoiceId == null) {
            return;
        }
        polling = true;
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
    }

    private void checkInvoiceStatus() {
        if (currentInvoiceId == null) {
            return;
        }
        new Thread(() -> {
            try {
                String status = new BTCPayApiClient(this).getInvoiceStatus(currentInvoiceId);
                Log.d(TAG, "Invoice status invoiceId=" + currentInvoiceId + " status=" + status);
                runOnUiThread(() -> handleInvoiceStatus(status));
            } catch (Exception e) {
                Log.w(TAG, "Invoice status check failed", e);
            }
        }).start();
    }

    private void handleInvoiceStatus(String status) {
        switch (status) {
            case "Settled":
            case "Processing":
                if (finalizingSale) {
                    return;
                }
                polling = false;
                handler.removeCallbacks(pollRunnable);
                finalizingSale = true;
                textStatus.setText("Recording Clover payment...");
                finalizeSettledSale();
                break;
            case "Expired":
            case "Invalid":
                handleSaleFailure("Invoice " + status.toLowerCase(Locale.US) + ".");
                break;
            default:
                textStatus.setText("Waiting for payment...");
                break;
        }
    }

    private void finalizeSettledSale() {
        new Thread(() -> {
            try {
                new CloverPaymentRecorder(this).recordPayment(
                        currentOrderId,
                        activeEmployeeId,
                        currentInvoiceId,
                        currentBaseAmountCents,
                        currentTipAmountCents);
                runOnUiThread(() -> {
                    textStatus.setText("Payment complete");
                    imageQr.setImageBitmap(null);
                    imageQr.setVisibility(View.GONE);
                    showSuccessState();
                    btnCancelSale.setVisibility(View.GONE);
                    btnNewSale.setVisibility(View.VISIBLE);
                    finalizingSale = false;
                    saleInProgress = false;
                });
            } catch (Exception e) {
                Log.e(TAG, "Clover payment recording failed", e);
                runOnUiThread(() -> handleSaleFailure("Clover record failed: " + e.getMessage()));
            }
        }).start();
    }

    private void cancelActiveSale() {
        polling = false;
        handler.removeCallbacks(pollRunnable);
        if (currentInvoiceId == null) {
            textStatus.setText("Sale canceled.");
            resetSaleUi();
            return;
        }

        btnCancelSale.setEnabled(false);
        textStatus.setText("Canceling sale...");
        String invoiceId = currentInvoiceId;

        new Thread(() -> {
            try {
                new BTCPayApiClient(this).invalidateInvoice(invoiceId);
                runOnUiThread(() -> {
                    btnCancelSale.setEnabled(true);
                    textStatus.setText("Sale canceled.");
                    resetSaleUi();
                });
            } catch (Exception e) {
                Log.e(TAG, "Invoice invalidation failed", e);
                runOnUiThread(() -> {
                    btnCancelSale.setEnabled(true);
                    textStatus.setText("Cancel failed: " + e.getMessage());
                });
            }
        }).start();
    }

    private void handleSaleFailure(String message) {
        polling = false;
        handler.removeCallbacks(pollRunnable);
        finalizingSale = false;
        saleInProgress = false;
        textStatus.setText(message);
        btnCancelSale.setVisibility(View.GONE);
        btnNewSale.setVisibility(View.VISIBLE);
        showSaleState();
    }

    private void resetSaleUi() {
        polling = false;
        handler.removeCallbacks(pollRunnable);
        saleInProgress = false;
        finalizingSale = false;
        currentOrderId = null;
        currentInvoiceId = null;
        currentBaseAmountCents = 0L;
        currentTipAmountCents = 0L;
        currentTotalAmountCents = 0L;
        enteredAmountDigits = "";
        enteredTipDigits = "";
        entryMode = ENTRY_MODE_AMOUNT;

        updateEntryUi();
        btnCancelSale.setEnabled(true);
        btnCancelSale.setVisibility(View.GONE);
        btnNewSale.setVisibility(View.GONE);
        imageQr.setImageBitmap(null);
        imageQr.setVisibility(View.GONE);
        textSuccessCheckmark.clearAnimation();
        textSuccessCheckmark.setVisibility(View.GONE);
        textStatus.setText("Enter an amount to begin.");
        updateBreakdown();
        showEntryState();
    }

    private void updateBreakdown() {
        boolean tippingEnabled = BTCPayApiClient.loadConfiguration(this).tippingEnabled;
        textSubtotal.setText("Subtotal: " + formatAmount(currentBaseAmountCents));
        textTip.setText("Tip: " + formatAmount(currentTipAmountCents));
        textTip.setVisibility(tippingEnabled ? View.VISIBLE : View.GONE);
        textTotal.setText("Total: " + formatAmount(currentTotalAmountCents));
    }

    private void updateConfigSummary() {
        BTCPayApiClient.Config config = BTCPayApiClient.loadConfiguration(this);
        btcpayConfigured = config.isConfigured();
        if (!btcpayConfigured) {
            textConfigStatus.setText("BTCPay settings required.");
            textConfigWarning.setVisibility(View.VISIBLE);
            btnCharge.setEnabled(false);
            return;
        }
        textConfigWarning.setVisibility(View.GONE);
        btnCharge.setEnabled(true);
        textConfigStatus.setText("BTCPay store: " + config.storeId);
    }

    private void showSettingsDialog() {
        if (!canManageSettings) {
            textStatus.setText("Settings are limited to owner, admin, or manager.");
            return;
        }

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        EditText editUrl = view.findViewById(R.id.edit_btcpay_url);
        EditText editStoreId = view.findViewById(R.id.edit_store_id);
        EditText editApiKey = view.findViewById(R.id.edit_api_key);
        CheckBox checkTippingEnabled = view.findViewById(R.id.check_tipping_enabled);
        TextView textSettingsStatus = view.findViewById(R.id.text_settings_status);

        BTCPayApiClient.Config config = BTCPayApiClient.loadConfiguration(this);
        editUrl.setText(config.baseUrl);
        editStoreId.setText(config.storeId);
        editApiKey.setText(config.apiKey);
        checkTippingEnabled.setChecked(config.tippingEnabled);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("BTCPay settings")
                .setView(view)
                .setPositiveButton("Test & Save", null)
                .setNegativeButton("Close", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = editUrl.getText().toString().trim();
            String storeId = editStoreId.getText().toString().trim();
            String apiKey = editApiKey.getText().toString().trim();
            boolean tippingEnabled = checkTippingEnabled.isChecked();
            textSettingsStatus.setText("Testing connection...");

            new Thread(() -> {
                try {
                    String storeName = BTCPayApiClient.testConnection(url, storeId, apiKey);
                    BTCPayApiClient.saveConfiguration(MainActivity.this, url, storeId, apiKey, tippingEnabled);
                    runOnUiThread(() -> {
                        textSettingsStatus.setText("Connected to: " + storeName);
                        updateConfigSummary();
                        tenderRegistrationRequested = false;
                        registerTender();
                        dialog.dismiss();
                    });
                } catch (Exception e) {
                    Log.w(TAG, "BTCPay settings validation failed", e);
                    runOnUiThread(() -> textSettingsStatus.setText("Error: " + e.getMessage()));
                }
            }).start();
        });
    }

    private void bindKeypad() {
        int[] digitButtons = new int[] {
                R.id.btn_digit_0, R.id.btn_digit_1, R.id.btn_digit_2, R.id.btn_digit_3, R.id.btn_digit_4,
                R.id.btn_digit_5, R.id.btn_digit_6, R.id.btn_digit_7, R.id.btn_digit_8, R.id.btn_digit_9
        };

        for (int buttonId : digitButtons) {
            Button button = findViewById(buttonId);
            button.setOnClickListener(v -> appendDigit(((Button) v).getText().toString()));
        }

        findViewById(R.id.btn_tip_18).setOnClickListener(v -> applyPresetTip(18));
        findViewById(R.id.btn_tip_20).setOnClickListener(v -> applyPresetTip(20));
        findViewById(R.id.btn_tip_25).setOnClickListener(v -> applyPresetTip(25));
        findViewById(R.id.btn_tip_custom).setOnClickListener(v -> showCustomTipEntry());
        findViewById(R.id.btn_backspace).setOnClickListener(v -> backspaceDigit());
        findViewById(R.id.btn_clear_amount).setOnClickListener(v -> clearAmount());
    }

    private void appendDigit(String digit) {
        if (saleInProgress) {
            return;
        }
        String digits = getActiveDigits();
        if ("0".equals(digits) && "0".equals(digit)) {
            return;
        }
        setActiveDigits(digits + digit);
        trimLeadingZeros();
        updateEntryUi();
    }

    private void backspaceDigit() {
        String digits = getActiveDigits();
        if (saleInProgress || digits.isEmpty()) {
            return;
        }
        setActiveDigits(digits.substring(0, digits.length() - 1));
        updateEntryUi();
    }

    private void clearAmount() {
        if (saleInProgress) {
            return;
        }
        setActiveDigits("");
        updateEntryUi();
    }

    private void trimLeadingZeros() {
        String digits = getActiveDigits();
        while (digits.length() > 1 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        setActiveDigits(digits);
    }

    private String getActiveDigits() {
        return entryMode == ENTRY_MODE_TIP_CUSTOM ? enteredTipDigits : enteredAmountDigits;
    }

    private void setActiveDigits(String digits) {
        if (entryMode == ENTRY_MODE_TIP_CUSTOM) {
            enteredTipDigits = digits;
        } else {
            enteredAmountDigits = digits;
        }
    }

    private long getCurrentEntryAmountCents() {
        String digits = getActiveDigits();
        if (digits.isEmpty()) {
            return 0L;
        }
        return Long.parseLong(digits);
    }

    private void updateEntryUi() {
        if (entryMode == ENTRY_MODE_TIP_OPTIONS) {
            textEntryLabel.setText("Tip");
            textEntryContext.setText("Choose tip for subtotal " + formatAmount(currentBaseAmountCents));
            textEntryTotalPreview.setVisibility(View.VISIBLE);
            textEntryTotalPreview.setText("Invoice total: " + formatAmount(currentBaseAmountCents));
            layoutTipPresets.setVisibility(View.VISIBLE);
            layoutKeypad.setVisibility(View.GONE);
            btnCharge.setText("No Tip");
            btnSecondaryAction.setVisibility(View.VISIBLE);
            btnSecondaryAction.setText("Back");
            btnCharge.setEnabled(btcpayConfigured);
            textAmountDisplay.setText(formatAmount(0L));
            return;
        }

        if (entryMode == ENTRY_MODE_TIP_CUSTOM) {
            textEntryLabel.setText("Tip");
            textEntryContext.setText("Subtotal: " + formatAmount(currentBaseAmountCents));
            textEntryTotalPreview.setVisibility(View.VISIBLE);
            textEntryTotalPreview.setText("Invoice total: " + formatAmount(currentBaseAmountCents + getCurrentEntryAmountCents()));
            layoutTipPresets.setVisibility(View.GONE);
            layoutKeypad.setVisibility(View.VISIBLE);
            btnCharge.setText("Create Invoice");
            btnSecondaryAction.setVisibility(View.VISIBLE);
            btnSecondaryAction.setText("Back");
        } else {
            textEntryLabel.setText("Amount");
            textEntryContext.setText("Enter sale amount");
            textEntryTotalPreview.setVisibility(View.GONE);
            layoutTipPresets.setVisibility(View.GONE);
            layoutKeypad.setVisibility(View.VISIBLE);
            btnCharge.setText("Charge");
            btnSecondaryAction.setVisibility(View.GONE);
        }
        btnCharge.setEnabled(btcpayConfigured);
        textAmountDisplay.setText(formatAmount(getCurrentEntryAmountCents()));
    }

    private void handleSecondaryAction() {
        if (saleInProgress) {
            return;
        }
        if (entryMode == ENTRY_MODE_TIP_CUSTOM) {
            entryMode = ENTRY_MODE_TIP_OPTIONS;
            enteredTipDigits = "";
            updateEntryUi();
            return;
        }
        if (entryMode == ENTRY_MODE_TIP_OPTIONS) {
            entryMode = ENTRY_MODE_AMOUNT;
            enteredTipDigits = "";
            updateEntryUi();
        }
    }

    private void applyPresetTip(int percent) {
        if (saleInProgress || currentBaseAmountCents <= 0L) {
            return;
        }
        startSale(currentBaseAmountCents, calculatePercentageTip(currentBaseAmountCents, percent));
    }

    private void showCustomTipEntry() {
        if (saleInProgress) {
            return;
        }
        entryMode = ENTRY_MODE_TIP_CUSTOM;
        enteredTipDigits = "";
        updateEntryUi();
    }

    private void showEntryState() {
        layoutSetupInfo.setVisibility(View.GONE);
        layoutEntryState.setVisibility(View.VISIBLE);
        layoutSaleState.setVisibility(View.GONE);
    }

    private void showSaleState() {
        layoutSetupInfo.setVisibility(View.GONE);
        layoutEntryState.setVisibility(View.GONE);
        layoutSaleState.setVisibility(View.VISIBLE);
    }

    private void showSuccessState() {
        textSuccessCheckmark.setScaleX(0.6f);
        textSuccessCheckmark.setScaleY(0.6f);
        textSuccessCheckmark.setAlpha(0f);
        textSuccessCheckmark.setVisibility(View.VISIBLE);

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(textSuccessCheckmark, View.SCALE_X, 0.6f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(textSuccessCheckmark, View.SCALE_Y, 0.6f, 1f);
        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(textSuccessCheckmark, View.ALPHA, 0f, 1f);
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(scaleX, scaleY, fadeIn);
        animatorSet.setDuration(280);
        animatorSet.start();
    }

    private long calculatePercentageTip(long baseAmountCents, int percent) {
        return Math.round(baseAmountCents * (percent / 100.0));
    }

    private String formatAmount(long amountCents) {
        return String.format(Locale.US, "%s%.2f", currency.getSymbol(), amountCents / 100.0);
    }

    private String formatPaymentMethod(String paymentMethodId) {
        if (paymentMethodId == null) {
            return "BTCPay";
        }
        switch (paymentMethodId) {
            case "BTC-LN":
                return "Lightning";
            case "BTC-CHAIN":
                return "Bitcoin";
            case "BTC-LNURL":
                return "LNURL";
            default:
                return paymentMethodId;
        }
    }

    private int qrSizePx() {
        return (int) (getResources().getDisplayMetrics().density * 280);
    }

    private Currency resolveCurrency() {
        try {
            return Currency.getInstance(Locale.getDefault());
        } catch (Exception e) {
            return Currency.getInstance("USD");
        }
    }
}
