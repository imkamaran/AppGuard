import sqlite3, sys, datetime
c = sqlite3.connect(sys.argv[1])
print("== hook_status")
for r in c.execute("select pkg, feature, ok, detail, pid from hook_status order by feature"): print(r)
print("== events")
for r in c.execute("select ts, pkg, pid, category, api, policy, action, outcome, count, detail from events order by id"):
    print(datetime.datetime.fromtimestamp(r[0]/1000).strftime('%H:%M:%S'), *r[1:])
