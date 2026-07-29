#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/netlife/speedtestnl/NperfGeckoActivity.java")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    "import androidx.annotation.NonNull;\n",
    "import androidx.activity.OnBackPressedCallback;\n"
    "import androidx.annotation.NonNull;\n",
    "OnBackPressedCallback import",
)

replace_once(
    "        progressBar = findViewById(R.id.progressNperfGecko);\n\n"
    "        Intent source = getIntent();",
    "        progressBar = findViewById(R.id.progressNperfGecko);\n\n"
    "        getOnBackPressedDispatcher().addCallback(this,\n"
    "            new OnBackPressedCallback(true) {\n"
    "                @Override\n"
    "                public void handleOnBackPressed() {\n"
    "                    fail(\"USER_CANCELLED\",\n"
    "                        \"La prueba nPerf fue cancelada por el usuario\");\n"
    "                }\n"
    "            });\n\n"
    "        Intent source = getIntent();",
    "back dispatcher registration",
)

old_accept = '''                .accept(
                    extension -> {
                        if (finished) return;
                        session.getWebExtensionController().setMessageDelegate(
                            extension, messageDelegate, NATIVE_APP);
                        requestLocationPermissionProactively();
                        setStatus("Cargando nPerf en GeckoView...");
                        session.loadUri(NPERF_URL);
                    },
                    error -> fail(
                        "EXTENSION_INSTALL",
                        "No se pudo instalar la automatización nPerf: " +
                            safeMessage(error)
                    )
                );
'''
new_accept = '''                .accept(
                    extension -> handler.post(() -> {
                        if (finished || session == null) return;
                        session.getWebExtensionController().setMessageDelegate(
                            extension, messageDelegate, NATIVE_APP);
                        requestLocationPermissionProactively();
                        setStatus("Cargando nPerf en GeckoView...");
                        session.loadUri(NPERF_URL);
                    }),
                    error -> handler.post(() -> fail(
                        "EXTENSION_INSTALL",
                        "No se pudo instalar la automatización nPerf: " +
                            safeMessage(error)
                    ))
                );
'''
replace_once(old_accept, new_accept, "UI-thread extension registration")

replace_once(
    '''
    @Override
    public void onBackPressed() {
        fail("USER_CANCELLED", "La prueba nPerf fue cancelada por el usuario");
    }
''',
    "\n",
    "legacy onBackPressed override",
)

for marker in (
    "new OnBackPressedCallback(true)",
    "extension -> handler.post(() ->",
):
    if marker not in text:
        raise RuntimeError(f"missing required marker: {marker}")

path.write_text(text, encoding="utf-8")
