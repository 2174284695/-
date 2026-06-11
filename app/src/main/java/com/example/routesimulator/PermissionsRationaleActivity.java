package com.example.routesimulator;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class PermissionsRationaleActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);
        content.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.health_rationale_title);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title);

        TextView explanation = new TextView(this);
        explanation.setText(R.string.health_rationale_text);
        explanation.setTextSize(16);
        explanation.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        textParams.topMargin = padding;
        content.addView(explanation, textParams);

        Button close = new Button(this);
        close.setText(R.string.back);
        close.setOnClickListener(view -> finish());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        buttonParams.topMargin = padding;
        content.addView(close, buttonParams);
        setContentView(content);
    }
}
