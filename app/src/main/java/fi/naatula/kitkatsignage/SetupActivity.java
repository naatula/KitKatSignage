package fi.naatula.kitkatsignage;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * First-run configuration: asks for the URL this device should display.
 *
 * The screen is built in code rather than from a layout resource because it is
 * a single column of four views with no theming beyond the platform default,
 * and because the app deliberately depends on no support/AndroidX libraries so
 * that it keeps installing on Android 4.4 devices.
 */
public class SetupActivity extends Activity {

    private EditText urlField;
    private TextView errorView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Opening the setup screen when a URL already exists would be a way
        // to re-point a running sign, so send the user straight on instead.
        if (SignageConfig.getStartUrl(this) != null) {
            startSignage();
            return;
        }

        setContentView(buildLayout());
    }

    private View buildLayout() {
        int padding = dp(24);
        int gap = dp(12);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText(R.string.setup_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        column.addView(title);

        TextView hint = new TextView(this);
        hint.setText(R.string.setup_hint);
        hint.setPadding(0, gap, 0, gap);
        column.addView(hint);

        urlField = new EditText(this);
        urlField.setHint(R.string.setup_url_hint);
        urlField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setSingleLine(true);
        column.addView(urlField);

        errorView = new TextView(this);
        errorView.setTextColor(0xFFCC0000);
        errorView.setPadding(0, gap, 0, 0);
        errorView.setVisibility(View.GONE);
        column.addView(errorView);

        Button saveButton = new Button(this);
        saveButton.setText(R.string.setup_save);
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });

        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        buttonParams.gravity = Gravity.END;
        buttonParams.topMargin = gap;
        column.addView(saveButton, buttonParams);

        // Landscape signage panels lose most of their height to the on-screen
        // keyboard, so the whole column has to be able to scroll.
        ScrollView scroller = new ScrollView(this);
        scroller.addView(column);

        return scroller;
    }

    private void save() {
        String url = SignageConfig.normalize(this, urlField.getText().toString());

        if (url == null) {
            errorView.setText(R.string.setup_invalid_url);
            errorView.setVisibility(View.VISIBLE);
            return;
        }

        SignageConfig.setStartUrl(this, url);
        startSignage();
    }

    private void startSignage() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
