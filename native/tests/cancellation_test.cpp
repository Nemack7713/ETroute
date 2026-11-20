#include "etroute/native_supervisor.h"
#include <atomic>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <unistd.h>
using namespace std::chrono_literals;
void require(bool ok, const char* text) { if (!ok) throw std::runtime_error(text); }
int main() {
    const auto root = std::filesystem::temp_directory_path() / ("etroute-cancel-" + std::to_string(getpid()));
    std::filesystem::create_directories(root);
    auto process = [&](const char* name, const char* script) {
        auto dir = root / name; std::filesystem::create_directories(dir);
        etroute::PreparedProcess p;
        p.executable = "/bin/sh"; p.argv = {"/bin/sh", "-c", script};
        p.working_directory = dir.string(); p.stdout_path = (dir / "out").string();
        p.stderr_path = (dir / "err").string(); p.timeout_ms = 3000; p.terminate_grace_ms = 30;
        return p;
    };
    auto early = process("early", "touch launched");
    auto result = etroute::run_process(early, [] { return true; });
    require(result.stage == etroute::SpawnStage::Cancelled && !result.timed_out, "early cancellation result");
    require(!std::filesystem::exists(root / "early/launched"), "early cancellation launched child");
    std::cout << "pre-launch cancellation: PASS\n";
    std::atomic_bool stop{false};
    auto active = process("active", "trap '' TERM; echo ready; while :; do :; done");
    auto other = process("other", "sleep 0.2; printf unaffected");
    etroute::NativeRunResult cancelled, unaffected;
    std::thread a([&] { cancelled = etroute::run_process(active, [&] { return stop.load(); }); });
    std::thread b([&] { unaffected = etroute::run_process(other); });
    bool ready = false;
    for (int i=0; i<200; ++i) {
        std::ifstream f(active.stdout_path); std::string line; std::getline(f,line);
        if(line == "ready") { ready=true; break; }
        std::this_thread::sleep_for(5ms);
    }
    stop.store(true); a.join(); b.join();
    require(ready, "worker readiness");
    require(cancelled.stage == etroute::SpawnStage::Cancelled && !cancelled.timed_out && cancelled.signal == 9, "TERM-resistant cancellation");
    require(cancelled.duration_ms < 2000, "cancellation latency");
    require(unaffected.succeeded(), "cancellation affected independent request");
    std::cout << "TERM-resistant cancellation: PASS\nindependent request: PASS\n";
    auto timeout = process("timeout", "while :; do :; done"); timeout.timeout_ms = 40;
    result = etroute::run_process(timeout, [] { return false; });
    require(result.timed_out && result.stage == etroute::SpawnStage::TimeoutKill, "timeout distinction");
    std::cout << "timeout distinction: PASS\n";
    std::filesystem::remove_all(root);
}
