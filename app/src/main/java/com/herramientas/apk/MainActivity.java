package com.herramientas.apk;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Menú principal: lista de herramientas. */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle(getString(R.string.app_name));

        LinearLayout list = findViewById(R.id.tool_list);
        addTool(list, "Información de APK", "Manifest, permisos, versiones (aapt-dump)", Tools.APK_INFO);
        addTool(list, "Información de DEX", "Cabecera, clases y métodos de classes.dex", Tools.DEX_INFO);
        addTool(list, "apktool decode", "Descompilar APK a fuentes (smali + recursos)", Tools.APKTOOL_DECODE);
        addTool(list, "apktool build", "Recompilar carpeta decodificada en APK", Tools.APKTOOL_BUILD);
        addTool(list, "baksmali", "classes.dex → smali", Tools.BAKSMALI);
        addTool(list, "smali", "smali → dex parcheado", Tools.SMALI);
        addTool(list, "Firmar APK", "Genera keystore y firma v1+v2 (uber-apk-signer)", Tools.SIGN);
        addTool(list, "Fusionar XAPK/splits", "Une varios APK en un universal APK (APKEditor)", Tools.MERGE_XAPK);
        addTool(list, "Listar contenido ZIP/APK", "Entradas y tamaños del archivo", Tools.ZIP_LIST);
        addTool(list, "Hashes MD5/SHA1/SHA256", "Sumas de verificación de archivos", Tools.HASHES);
        addTool(list, "Visor hexadecimal", "Volcado hex de cualquier archivo", Tools.HEX_VIEW);
        addTool(list, "Base64 encode/decode", "Texto ↔ Base64", Tools.BASE64);
        addTool(list, "Búsqueda en texto", "grep sobre archivos de trabajo", Tools.TEXT_SEARCH);
        addTool(list, "Instalar APK", "Abre el instalador con un APK firmado", Tools.INSTALL_APK);

        ensureStorage();
    }

    private void addTool(LinearLayout parent, String name, String desc, final int id) {
        Button b = new Button(this);
        b.setText(name);
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, ToolActivity.class);
                i.putExtra(ToolActivity.EXTRA_TOOL, id);
                startActivity(i);
            }
        });
        parent.addView(b);
        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(12);
        d.setTextColor(0xFF777777);
        d.setPadding(24, 0, 0, 12);
        parent.addView(d);
    }

    private void ensureStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivityForResult(i, REQ_PERM);
                } catch (Exception ignored) {
                }
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            List<String> need = new ArrayList<>();
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED)
                need.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED)
                need.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            if (!need.isEmpty())
                requestPermissions(need.toArray(new String[0]), REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] g) {
        if (rc == REQ_PERM && Build.VERSION.SDK_INT < 30
                && (g.length == 0 || allDenied(g))) {
            Toast.makeText(this, "Sin permiso de almacenamiento no se pueden leer/escribir archivos",
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean allDenied(int[] g) {
        for (int x : g) if (x >= 0) return false;
        return true;
    }
}
