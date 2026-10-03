package com.example.notifreader;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class LoginActivity extends AppCompatActivity {

    private EditText phone, otp;
    private Button send, verify;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Already logged in: go straight to the main screen
        if (SecureStore.get(this, "cid") != null) {
            goMain();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 160, 48, 48);

        TextView title = new TextView(this);
        title.setText("Login");
        title.setTextSize(24f);
        title.setPadding(0, 0, 0, 32);

        phone = new EditText(this);
        phone.setHint("Registered phone number");
        phone.setInputType(InputType.TYPE_CLASS_PHONE);

        send = new Button(this);
        send.setText("Send OTP");

        otp = new EditText(this);
        otp.setHint("Enter OTP");
        otp.setInputType(InputType.TYPE_CLASS_NUMBER);
        otp.setVisibility(View.GONE);

        verify = new Button(this);
        verify.setText("Verify and login");
        verify.setVisibility(View.GONE);

        status = new TextView(this);
        status.setPadding(0, 24, 0, 0);

        send.setOnClickListener(v -> {
            String p = phone.getText().toString().trim();
            if (p.isEmpty()) {
                status.setText("Enter your phone number.");
                return;
            }
            send.setEnabled(false);
            status.setText("Sending OTP...");
            ApiClient.sendOtp(p, (ok, msg) -> {
                send.setEnabled(true);
                status.setText(msg);
                if (ok) {
                    otp.setVisibility(View.VISIBLE);
                    verify.setVisibility(View.VISIBLE);
                }
            });
        });

        verify.setOnClickListener(v -> {
            String p = phone.getText().toString().trim();
            String code = otp.getText().toString().trim();
            if (code.isEmpty()) {
                status.setText("Enter the OTP.");
                return;
            }
            verify.setEnabled(false);
            status.setText("Verifying...");
            ApiClient.verifyOtp(this, p, code, (ok, msg) -> {
                verify.setEnabled(true);
                if (ok) goMain(); else status.setText(msg);
            });
        });

        root.addView(title);
        root.addView(phone);
        root.addView(send);
        root.addView(otp);
        root.addView(verify);
        root.addView(status);
        setContentView(root);
    }

    private void goMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
