import os
import socket
import unittest
from unittest import mock

import preflight


class VersionParsingTest(unittest.TestCase):
    def test_reads_java_banner(self):
        banner = ('openjdk version "17.0.20.1" 2026-01-20\n'
                  'OpenJDK Runtime Environment Homebrew (build 17.0.20.1+0)\n')
        self.assertEqual(preflight.parse_version(banner), (17, 0, 20))

    def test_reads_maven_banner(self):
        self.assertEqual(preflight.parse_version("Apache Maven 3.9.16 (2bdd9fd)"), (3, 9, 16))

    def test_returns_none_without_a_version(self):
        self.assertIsNone(preflight.parse_version("command not found"))


class JavaCheckTest(unittest.TestCase):
    def test_missing_runtime_names_the_remedy(self):
        with mock.patch.object(preflight, "run_tool", return_value=(False, "")):
            check = preflight.check_java()
        self.assertFalse(check.ok)
        self.assertTrue(check.failed)
        self.assertIn("JAVA_HOME", check.remedy)

    def test_wrong_major_version_is_rejected(self):
        with mock.patch.object(preflight, "run_tool",
                               return_value=(True, 'openjdk version "21.0.1"')):
            check = preflight.check_java()
        self.assertFalse(check.ok)
        self.assertIn("21", check.detail)

    def test_java_17_passes(self):
        with mock.patch.object(preflight, "run_tool",
                               return_value=(True, 'openjdk version "17.0.20.1"')):
            check = preflight.check_java()
        self.assertTrue(check.ok)

    def test_java_home_without_binary_fails_before_running_anything(self):
        with mock.patch.dict(os.environ, {"JAVA_HOME": "/nonexistent/jdk"}):
            self.assertIsNone(preflight.java_command())
            check = preflight.check_java()
        self.assertFalse(check.ok)
        self.assertIn("bin/java", check.detail)


class MavenCheckTest(unittest.TestCase):
    def test_old_maven_is_rejected(self):
        with mock.patch.object(preflight, "run_tool",
                               return_value=(True, "Apache Maven 3.6.3")):
            check = preflight.check_maven()
        self.assertFalse(check.ok)

    def test_supported_maven_passes(self):
        with mock.patch.object(preflight, "run_tool",
                               return_value=(True, "Apache Maven 3.9.16")):
            self.assertTrue(preflight.check_maven().ok)


class PortCheckTest(unittest.TestCase):
    def test_detects_an_occupied_port(self):
        with socket.socket() as holder:
            holder.bind(("127.0.0.1", 0))
            holder.listen(1)
            port = holder.getsockname()[1]
            self.assertFalse(preflight.port_is_free(port))

    def test_free_port_is_reported_free(self):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        self.assertTrue(preflight.port_is_free(port))

    def test_occupied_compose_port_names_the_override_variable(self):
        with socket.socket() as holder:
            holder.bind(("127.0.0.1", 0))
            holder.listen(1)
            port = holder.getsockname()[1]
            with mock.patch.dict(os.environ, {"REDIS_PORT": str(port)}):
                checks = {check.name: check for check in preflight.check_ports()}
        redis = checks["port redis"]
        self.assertFalse(redis.ok)
        self.assertIn("REDIS_PORT", redis.remedy)

    def test_rejects_an_unusable_port_value(self):
        with mock.patch.dict(os.environ, {"POSTGRES_PORT": "not-a-port"}):
            checks = {check.name: check for check in preflight.check_ports()}
        self.assertFalse(checks["port postgres"].ok)
        with mock.patch.dict(os.environ, {"POSTGRES_PORT": "70000"}):
            checks = {check.name: check for check in preflight.check_ports()}
        self.assertFalse(checks["port postgres"].ok)

    def test_blank_override_falls_back_to_the_default(self):
        with mock.patch.dict(os.environ, {"POSTGRES_PORT": ""}):
            self.assertEqual(preflight.resolve_port("POSTGRES_PORT", 5432), 5432)


class ComposeVersionTest(unittest.TestCase):
    def test_rejects_compose_without_reset_support(self):
        with mock.patch.object(preflight, "run_tool",
                               return_value=(True, "Docker Compose version v2.20.2")):
            check = preflight.check_compose()
        self.assertFalse(check.ok)
        self.assertIn("!reset", check.remedy)

    def test_accepts_compose_with_reset_support(self):
        for banner in ("Docker Compose version v2.24.0", "Docker Compose version v5.3.1"):
            with mock.patch.object(preflight, "run_tool", return_value=(True, banner)):
                self.assertTrue(preflight.check_compose().ok, banner)

    def test_missing_compose_names_the_plugin(self):
        with mock.patch.object(preflight, "run_tool", return_value=(False, "")):
            check = preflight.check_compose()
        self.assertFalse(check.ok)
        self.assertIn("Compose v2 plugin", check.remedy)


class ModeTest(unittest.TestCase):
    def test_app_mode_checks_only_the_application_port(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            names = [check.name for check in preflight.check_ports("app")]
        self.assertEqual(names, ["port app"])

    def test_host_mode_checks_every_backing_port(self):
        names = [check.name for check in preflight.check_ports()]
        self.assertEqual(names, ["port postgres", "port redis",
                                 "port rabbitmq", "port rabbitmq-management"])

    def test_app_port_override_is_honoured(self):
        with mock.patch.dict(os.environ, {"APP_PORT": "18080"}):
            self.assertEqual(preflight.resolve_port("APP_PORT", 8080), 18080)

    def test_app_mode_does_not_fail_on_a_missing_jdk(self):
        # The image build supplies the JDK, so app mode reports it without failing.
        # Fresh Check objects per call: collect("app") relaxes the two it owns,
        # and a shared instance would leak that into the host assertion.
        with mock.patch.object(preflight, "check_java",
                               side_effect=lambda: preflight.Check("JDK 17", False, "none", "install")), \
             mock.patch.object(preflight, "check_maven",
                               side_effect=lambda: preflight.Check("Maven", False, "none", "install")), \
             mock.patch.object(preflight, "check_docker",
                               side_effect=lambda: preflight.Check("Docker", True, "ok")), \
             mock.patch.object(preflight, "check_compose",
                               side_effect=lambda: preflight.Check("Docker Compose", True, "ok")), \
             mock.patch.object(preflight, "check_ports", side_effect=lambda mode="host": []), \
             mock.patch.object(preflight, "check_image_cache",
                               side_effect=lambda: preflight.Check("cache", True, "", required=False)):
            app = preflight.collect("app")
            host = preflight.collect("host")
        self.assertEqual([c for c in app if c.failed], [])
        self.assertEqual([c.name for c in host if c.failed], ["JDK 17", "Maven"])


class ReportTest(unittest.TestCase):
    def test_cache_state_note_does_not_fail_the_run(self):
        note = preflight.Check("cache state (cold)", True, "0/3 images", required=False)
        self.assertFalse(note.failed)

    def test_exit_status_follows_required_failures(self):
        import io
        passing = [preflight.Check("JDK 17", True, "Java 17.0.1")]
        self.assertEqual(preflight.report(passing, io.StringIO()), 0)
        failing = passing + [preflight.Check("Docker", False, "not running", "Start Docker.")]
        self.assertEqual(preflight.report(failing, io.StringIO()), 1)


if __name__ == "__main__":
    unittest.main()
