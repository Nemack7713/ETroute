#include <Python.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// Fixed feasibility probe only. No -c, script file, import list or general interpreter arguments.
int main(int argc, char **argv) {
    if (argc != 3 || strcmp(argv[1], "--probe") != 0 || argv[2][0] != '/') return 64;
    char stdlib[4096], dynload[4096];
    if (snprintf(stdlib, sizeof(stdlib), "%s/lib/python3.14", argv[2]) >= (int)sizeof(stdlib) ||
        snprintf(dynload, sizeof(dynload), "%s/lib/python3.14/lib-dynload", argv[2]) >= (int)sizeof(dynload)) return 64;
    PyConfig config;
    PyConfig_InitIsolatedConfig(&config);
    config.site_import = 0;
    config.write_bytecode = 0;
    config.use_system_logger = 0;
    config.buffered_stdio = 0;
    config.module_search_paths_set = 1;
    PyStatus status = PyConfig_SetBytesString(&config, &config.home, argv[2]);
    wchar_t *paths[] = {Py_DecodeLocale(stdlib, NULL), Py_DecodeLocale(dynload, NULL)};
    for (int i = 0; i < 2 && !PyStatus_Exception(status); i++) {
        if (!paths[i]) { PyConfig_Clear(&config); return 70; }
        status = PyWideStringList_Append(&config.module_search_paths, paths[i]);
    }
    for (int i = 0; i < 2; i++) PyMem_RawFree(paths[i]);
    if (!PyStatus_Exception(status)) status = Py_InitializeFromConfig(&config);
    if (PyStatus_Exception(status)) {
        fprintf(stderr, "ETROUTE_PYTHON_INIT_FAILED: %.512s\n", status.err_msg ? status.err_msg : "unknown");
        PyConfig_Clear(&config); return 70;
    }
    PyConfig_Clear(&config);
    int result = PyRun_SimpleString(
        "import sys, json, math, hashlib, ssl, sqlite3\n"
        "assert sys.version_info[:3] == (3, 14, 4)\n"
        "assert math.isqrt(81) == 9\n"
        "assert hashlib.sha256(b'etroute').hexdigest()\n"
        "print('ETROUTE_PYTHON_PROBE_OK ' + json.dumps({'python': sys.version.split()[0], 'openssl': ssl.OPENSSL_VERSION, 'sqlite': sqlite3.sqlite_version}), flush=True)\n");
    int finalized = Py_FinalizeEx();
    return result == 0 && finalized == 0 ? 0 : 71;
}
