# Adding an HSM to a CloudHSM cluster: what happens to changes made during the join

**[中文版 / Chinese version](CLOUDHSM_ADD_HSM_FAQ.zh-CN.md)** — this file stays authoritative; CI's
`doc-parity` job fails the build if the two disagree on structure, command output or measured values.

**Audience:** a PSP's platform or security team planning a CloudHSM capacity change.
**Status:** every answer below was **measured on real CloudHSM hardware** (`hsm2m.medium`, FIPS mode,
Client SDK 5.18.0) on a throwaway cluster that was destroyed afterwards. Where something was not
measured, it says so. Exact command output is quoted so you can compare it against your own cluster.

---

## The short version

Adding an HSM restores a **point-in-time snapshot** of an existing HSM onto the new one. Anything
created *after* that snapshot is taken is not in it, and what happens next **depends on what you
created**:

| You created, during the join | Does it reach the new HSM on its own? |
|---|---|
| A **key** | **Yes.** Server-side synchronisation periodically clones keys to every HSM. No action needed. |
| A **user** | **No. Never.** There is no server-side user synchronisation. Measured: still diverged after 14.6 minutes, and it does not heal. |
| An **mTLS trust anchor** (or any policy) | **No. Never.** Same reason — policies are not resynchronised. |

**So the rule is one sentence:** add the HSM, wait for it to reach `ACTIVE`, and only then create or
change users, or register or deregister an mTLS trust anchor. Keys are safe to create at any time
(subject to the separate quorum considerations in `README-CloudHSM.md`).

---

## Q1. What exactly does "the join takes a snapshot" mean, and when is it taken?

AWS documents that adding an HSM makes a backup of **all keys, users, and policies** on an existing
HSM and restores that backup onto the new one.

AWS does not expose *when* the backup is taken, so it was measured from the outside: ten users were
created thirty seconds apart across the whole join window, then their coverage was read once the new
HSM was `ACTIVE`.

```text
create-hsm issued at epoch 1790239945
  u01  created t+4s    -> cluster-coverage "full"
  u02  created t+34s   -> cluster-coverage "full"
  u03  created t+65s   -> cluster-coverage "inconsistent"
  u04  created t+96s   -> cluster-coverage "inconsistent"
  ...
  u10  created t+280s  -> cluster-coverage "inconsistent"
second HSM reached ACTIVE at t+313s
```

The boundary is visible: users created in the **first ~34 seconds** were in the snapshot and arrived on
the new HSM; everything from **~65 seconds onward** was not. On this cluster the snapshot was taken
somewhere between those two points.

**Do not treat ~34 seconds as a safe window.** It is one measurement on one cluster, the timing is not
documented, and it will vary. The safe procedure is to wait for `ACTIVE`, not to move quickly.

## Q2. If a key is created during the join, is it lost?

No. Keys have a server-side fallback: AWS describes server-side synchronisation as periodically cloning
keys to every HSM in the cluster, requiring no management. The key is on fewer HSMs than you expect for
a while, and then it is on all of them.

Two caveats that matter operationally, both documented by AWS:

- **The catch-up interval is not published.** AWS says only that it "can vary, depending on the workload
  of your cluster and other intangibles". You cannot bound it from documentation; use CloudWatch to
  establish what your cluster actually does.
- **A call using a brand-new key can fail** if it happens to be routed to an HSM that does not have the
  key yet. AWS's stated mitigation is application-level retry immediately after key creation.

## Q3. If a user is created during the join, what happens?

It stays on the old HSMs and never reaches the new one. There is no mechanism that fixes it.

AWS states the reason plainly: *"Unlike keys, there is **no server-side mechanism** to synchronize HSM
users across the cluster."* The CLI synchronises on a best-effort basis **at the moment you run the
command**, to the HSMs it can reach then — and the joining HSM is not one of them.

Measured, with time as the control: 879 seconds (14.6 minutes) after the new HSM reached `ACTIVE`, six
diverged users were still diverged, while two that had been repaired by hand in the same interval were
fine. Time is not the variable; the repair is.

```text
checked 879s after the new HSM became ACTIVE
  u03  full           <- had been repaired by hand
  u05  inconsistent
  u06  inconsistent
  u07  inconsistent
  u08  inconsistent
  u09  inconsistent
  u10  inconsistent
```

**Why this is worse than it sounds.** A diverged user produces an *intermittent* failure, not a clean
one. Client connections are load-balanced across HSMs, so the same login succeeds or fails depending on
which HSM it lands on. That is one of the harder faults to diagnose from the application side.

## Q4. How do I tell whether this has happened to me?

`user list` reports a `cluster-coverage` field per user. `"full"` means every HSM currently in the
cluster has it; **`"inconsistent"` means some do and some do not**, and that string is the signal.

```json
{ "username": "u05", "role": "crypto-user", "locked": "false",
  "mfa": [], "quorum": [], "cluster-coverage": "inconsistent" }
```

For mTLS trust anchors the equivalent is `cluster mtls list-trust-anchors`, which reports
`cluster-coverage` per anchor.

For **keys**, use CloudWatch instead: `HsmKeysTokenOccupied` in the `AWS/CloudHSM` namespace is reported
per HSM instance, and AWS's monitoring guidance recommends alarming on *"differences in HSM user or key
count to identify synchronization issues"*. `HsmUsersAvailable` gives the same handle for users.

> **One trap with `cluster-coverage`, measured.** `"full"` means *present on every HSM currently in the
> cluster* — it is **not** a redundancy or durability measure. A trust anchor registered while the
> cluster had one HSM reported `"full"` at that moment, and the **same anchor, unchanged**, reported
> `"inconsistent"` once a second HSM joined. So always establish how many HSMs are `ACTIVE` before you
> interpret a coverage string. The client can count them (`cluster hsm-info`) but cannot name them — it
> reports serial numbers, not HSM IDs, so anything needing an HSM ID comes from `describe-clusters`.

## Q5. Can a diverged user be repaired, and how?

Yes, and it is clean. AWS's guidance is to finish the operation you started, and that is what was
measured:

- **The user should exist** → run `user create` again for the same username and role. Measured:
  `error_code 0`, and coverage moved `inconsistent` → `full`.
- **The user should not exist** → run `user delete` for that username under **both** roles. Measured:
  the delete under the correct role returned `error_code 0`, the delete under the other role returned
  `"Specified user does not exist"`, which is harmless, and the user disappeared from `user list`
  entirely.

**One ordering rule from AWS that will bite you if you ignore it:** if the **admin account itself** is
inconsistent, repair the admin first — you need a consistent admin before you can use it to repair
anyone else.

**One inconsistency has no in-place repair.** If a user's `role` reads `inconsistent`, the user is a
crypto user on some HSMs and an admin on others. You must delete it under both roles and re-create it.
AWS's stated cause is two SDKs creating the same username at the same time with different roles — so
provision users from one place, serially.

## Q6. Can a diverged mTLS trust anchor be repaired? (Read the answer carefully — the command lies.)

Yes, and **the repair works while reporting an error**. This is the single most misleading behaviour
found in this whole area.

A genuinely diverged anchor was manufactured by registering it during a join window, then repaired by
re-running the registration exactly as AWS's troubleshooting page advises:

```console
before:  "certificate-reference": "0x02",  "cluster-coverage": "inconsistent"

$ cloudhsm-cli cluster mtls register-trust-anchor --path ca2.crt
{
  "error_code": 1,
  "data": "Certificate error received from Hsm. Trust anchor is already installed in Hsm."
}

after:   "certificate-reference": "0x02",  "cluster-coverage": "full"
```

**`error_code 1`, and the anchor was repaired.** The error comes from the HSM that already had the
anchor; the HSM that was missing it received it. An operator who reads the non-zero exit as failure and
does not re-check coverage will conclude the repair did not work — when it did.

> **Always verify a trust-anchor repair by re-reading `cluster mtls list-trust-anchors`, never by the
> command's exit code.**

There is also a distinguishable difference in the message text, though it is undocumented and should
not be leaned on:

| Situation | Message |
|---|---|
| Anchor already on **every** HSM (nothing to repair) | `"Invalid Certificate: Trust anchor already exists."` |
| Anchor **missing from some** HSMs (repair performed) | `"Certificate error received from Hsm. Trust anchor is already installed in Hsm."` |

If you prefer an unambiguous path, deregister and re-register instead — measured to work cleanly:

```console
$ cloudhsm-cli cluster mtls deregister-trust-anchor --certificate-reference 0x02
{ "error_code": 0, "data": { "message": "Trust anchor with reference 0x02 deregistered successfully" } }

$ cloudhsm-cli cluster mtls register-trust-anchor --path ca2.crt
{ "error_code": 0, ... "cluster-coverage": "full" }
```

Note the cost of that route: a cluster holds **at most two** trust anchors, so deregistering to
re-register is only safe when you can afford the anchor to be absent briefly, and rotation with both
slots occupied requires freeing one first.

## Q7. Does any of this apply to the Pix signing key or the mTLS client key?

Both are **token keys**, so both are covered by automatic key synchronisation. Neither is a user or a
policy. In this workload they are generated once at provisioning and again only at rotation — both
planned activities — so the practical exposure is small.

The exposure that remains is **operational**: if a capacity change and a credential or trust-anchor
change are scheduled in the same maintenance window, the second can silently half-apply. Sequence them.

---

## Checklist for an HSM capacity change

1. Note how many HSMs are `ACTIVE` before you start (`describe-clusters`).
2. Add the HSM. **Change nothing else while it joins** — no user creation, deletion or password change,
   no trust-anchor registration or deregistration, no mTLS enforcement change.
3. Wait for the new HSM to reach `ACTIVE` and confirm the expected count from `describe-clusters`.
4. Now make any user or policy changes.
5. Verify: `user list` shows no `"inconsistent"`, and `cluster mtls list-trust-anchors` shows `full` for
   every anchor you expect — **interpreted against the HSM count from step 3**, because `full` only ever
   means "every HSM currently present".
6. Keep a standing CloudWatch alarm on per-HSM differences in `HsmKeysTokenOccupied` and
   `HsmUsersAvailable`, so a divergence is reported rather than discovered by a failing transaction.

---

## What was not measured, and should not be inferred

- **The snapshot instant is not a published or stable value.** The ~34-to-65-second bracket is one
  observation on one cluster and must not be used as a safe window.
- **The server-side key synchronisation interval was not measured here.** Keys were not created in the
  join window during this exercise; the key behaviour above is AWS's documentation plus this
  repository's separate quorum measurements, not a direct observation of key catch-up timing.
- **Divergence was only produced via the join window.** A partially failed `user create` or
  `register-trust-anchor` on a healthy cluster may or may not behave identically; that path was not
  tested.
- **Users diverged for 14.6 minutes without healing.** That is long enough to disprove prompt automatic
  repair and consistent with AWS's statement that no such mechanism exists. It is not proof that nothing
  would ever happen over days.
