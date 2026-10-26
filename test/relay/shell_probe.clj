(ns relay.shell-probe
  "A command for the steward's shell that tries every way out it can: TCP
  and UDP to the internet and to the host's side of the VM network, DNS,
  vsock to the host, and any unix socket in the filesystem. Each line it
  prints is `<what> fail <why>` or `<what> OPEN`. Shared by the live test
  (shell-live-test) and the pentest (scenarios/shell)."
  (:require [clojure.string :as str]))

(def ^:private script
  "import os, socket
def t(name, f):
    try:
        f(); print(name, 'OPEN')
    except Exception as e:
        print(name, 'fail', type(e).__name__)
def tcp(host, port):
    socket.create_connection((host, port), timeout=3).close()
def udp(host):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(2)
    s.sendto(b'x', (host, 53)); s.recvfrom(16)
def vsock(cid, port):
    s = socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM); s.settimeout(2); s.connect((cid, port)); s.close()
for host, port in [('1.1.1.1', 443), ('8.8.8.8', 53), ('192.168.64.1', 22), ('192.168.64.1', 53), ('10.0.2.2', 22), ('172.17.0.1', 2375)]:
    t('tcp %s:%d' % (host, port), lambda: tcp(host, port))
for host in ['1.1.1.1', '192.168.64.1']:
    t('udp %s:53' % host, lambda: udp(host))
t('dns example.com', lambda: socket.getaddrinfo('example.com', 443))
t('dns host.docker.internal', lambda: socket.getaddrinfo('host.docker.internal', 80))
for cid in [1, 2]:
    bad = []
    for p in list(range(1, 2049)) + [2375, 8080, 8088, 50051]:
        try:
            vsock(cid, p); bad.append(p)
        except Exception:
            pass
    print('vsock cid %d' % cid, ('OPEN %s' % bad) if bad else 'fail none')
socks = os.popen('find / -xdev -type s 2>/dev/null; find /run /tmp /scratch /var -type s 2>/dev/null').read().split()
print('unix sockets', ('OPEN %s' % socks) if socks else 'fail none')
")

(defn command
  "The shell command that runs the probe."
  []
  (str "python3 - <<'PROBE'\n" script "PROBE\n"))

(defn results
  "The probe's output as {what outcome}, outcome \"fail\" or \"OPEN\"."
  [out]
  (into {} (keep #(when-let [[_ what outcome] (re-matches #"(.+?) (fail|OPEN)\b.*" %)] [what outcome])
                 (str/split-lines (str out)))))
