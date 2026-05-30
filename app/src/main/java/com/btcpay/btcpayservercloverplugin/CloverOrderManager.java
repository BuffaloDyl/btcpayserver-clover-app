package com.buffalodyl.btcpayservercloverplugin;

import android.accounts.Account;
import android.content.Context;
import com.clover.sdk.util.CloverAccount;
import com.clover.sdk.v3.base.Reference;
import com.clover.sdk.v3.order.LineItem;
import com.clover.sdk.v3.order.Order;
import com.clover.sdk.v3.order.OrderConnector;

public class CloverOrderManager {
    private final Context context;

    public CloverOrderManager(Context context) {
        this.context = context;
    }

    public String createManualOrder(String employeeId, long amountCents, long tipAmountCents, String title) throws Exception {
        Account account = CloverAccount.getAccount(context);
        if (account == null) {
            throw new IllegalStateException("No Clover account available");
        }

        OrderConnector orderConnector = new OrderConnector(context, account, null);
        orderConnector.connect();
        try {
            Order order = new Order()
                    .setManualTransaction(true)
                    .setTitle(title)
                    .setNote("BTCPay POS sale");
            if (employeeId != null && !employeeId.isEmpty()) {
                order.setEmployee(new Reference().setId(employeeId));
            }

            Order createdOrder = orderConnector.createOrder(order);
            if (createdOrder == null || createdOrder.getId() == null) {
                throw new IllegalStateException("Could not create Clover order");
            }

            LineItem lineItem = new LineItem()
                    .setName(title)
                    .setPrice(amountCents);
            orderConnector.addCustomLineItem(createdOrder.getId(), lineItem, false);
            return createdOrder.getId();
        } finally {
            orderConnector.disconnect();
        }
    }
}
