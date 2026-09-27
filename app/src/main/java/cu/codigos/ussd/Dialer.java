package cu.codigos.ussd;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import java.util.List;

public class Dialer {

    public static void dial(Context ctx, String code) {
        // Sanitizar: conservar solo dígitos, *, # y + (los códigos USSD terminan en #)
        String clean = code.replaceAll("[^0-9*#+]", "");
        if (clean.isEmpty()) return;
        Uri uri = Uri.fromParts("tel", clean, null);
        Intent it = new Intent(Intent.ACTION_CALL, uri);
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                ctx.startActivity(it);
                return;
            } catch (SecurityException ignored) {
            }
        }
        Intent dial = new Intent(Intent.ACTION_DIAL, uri);
        try {
            ctx.startActivity(dial);
        } catch (Exception e) {
            Toast.makeText(ctx, "No se pudo abrir el marcador", Toast.LENGTH_SHORT).show();
        }
    }

    /** Muestra diálogo para rellenar parámetros del código (si los tiene) y luego marca. */
    public static void handleCode(final Context ctx, final Code c, final Runnable afterDial) {
        if (c.url != null && !c.url.isEmpty()) {
            openUrl(ctx, c.url);
            if (afterDial != null) afterDial.run();
            return;
        }
        if (!c.params.isEmpty()) {
            showParamsDialog(ctx, c, afterDial);
        } else {
            dial(ctx, c.code);
            if (afterDial != null) afterDial.run();
        }
    }

    /** Texto del botón principal según el tipo de entrada (USSD, teléfono o web). */
    public static String actionLabel(Code c) {
        if (c.url != null && !c.url.isEmpty()) return "Abrir";
        String clean = c.code.replaceAll("[^0-9*#+]", "");
        if (clean.isEmpty()) return "Copiar";
        if (!clean.startsWith("*") && !clean.startsWith("#")) return "Llamar";
        return "Marcar";
    }

    private static void showParamsDialog(final Context ctx, final Code c, final Runnable afterDial) {
        LinearLayout lay = new LinearLayout(ctx);
        lay.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        lay.setPadding(pad, pad / 2, pad, 0);

        TextView warn = new TextView(ctx);
        warn.setText("Escribe solo los valores (sin * ni #). Ej.: número 5xxxxxxxx.");
        warn.setTextSize(12.5f);
        lay.addView(warn);

        final EditText[] eds = new EditText[c.params.size()];
        for (int i = 0; i < c.params.size(); i++) {
            EditText e = new EditText(ctx);
            e.setHint(c.params.get(i));
            e.setInputType(InputType.TYPE_CLASS_TEXT);
            e.setMaxLines(1);
            eds[i] = e;
            lay.addView(e);
        }

        ScrollView sv = new ScrollView(ctx);
        sv.addView(lay);

        new AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.params_title) + " — " + c.title)
                .setView(sv)
                .setNegativeButton(R.string.params_cancel, null)
                .setPositiveButton(R.string.params_ok, (d, w) -> {
                    StringBuilder sb = new StringBuilder();
                    for (EditText e : eds) {
                        String v = e.getText().toString().replaceAll("[^0-9*#+a-zA-Z]", "");
                        sb.append("*").append(v);
                    }
                    String built = buildParametrized(c.code, sb.toString());
                    dial(ctx, built);
                    if (afterDial != null) afterDial.run();
                })
                .show();
    }

    /** Abre una URL (portales ETECSA, panel del router…) en el navegador. */
    public static void openUrl(Context ctx, String url) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Exception e) {
            Toast.makeText(ctx, "No hay navegador para abrir: " + url, Toast.LENGTH_LONG).show();
        }
    }

    /** Inserta los parámetros antes del # final del código plantilla. */
    static String buildParametrized(String template, String paramsJoined) {
        String t = template;
        // reemplazar tokens tipo *Número*, *Clave*, *PIN* etc. por los valores en orden
        if (t.endsWith("#")) {
            return t.substring(0, t.length() - 1) + paramsJoined + "#";
        }
        return t + paramsJoined + "#";
    }
}
