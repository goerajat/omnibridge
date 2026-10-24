---
name: aws-latency-test
description: Provision the AWS stack with Terraform, deploy the current code, run the FIX latency test with its sockperf network baseline, summarize the results against earlier runs, and destroy the stack.
argument-hint: "[warmup test-orders rate] [--no-tune-simulator] [--no-persistence] [--skip-baseline] [--keep]"
disable-model-invocation: true
allowed-tools:
  - Bash(./scripts/deploy/terraform-deploy.sh:*)
  - Bash(./scripts/deploy/deploy-pipeline.sh:*)
  - Bash(./scripts/deploy/wait-for-hosts.sh:*)
  - Bash(./scripts/deploy/run-aws-latency-test.sh:*)
  - Bash(./scripts/deploy/manage-services.sh:*)
  - Bash(terraform:*)
  - Bash(mvn install:*)
  - Bash(aws sts get-caller-identity:*)
  - Bash(ssh:*)
  - Bash(jq:*)
  - Bash(git status:*)
  - Bash(git rev-parse:*)
  - Bash(git diff:*)
  - Bash(git checkout -- infrastructure/terraform/environments/production/terraform.tfvars)
  - Bash(git add docs/latency-history.jsonl)
  - Bash(git commit:*)
---

# AWS latency test

Runs the current checkout on a fresh AWS stack, measures it, reports, and tears
the stack down. Invoking this skill is the user's approval for every step below,
including `terraform apply` and `terraform destroy` of the production
environment in `infrastructure/terraform/environments/production`. It is not
approval for anything else: never push, never change other infrastructure.

Arguments: `$ARGUMENTS`

- Up to three numbers: warmup orders, test orders, rate (defaults 10000 1000 100).
  Keep the defaults unless the user gives others, so runs stay comparable.
- `--no-tune-simulator`, `--no-persistence`, `--skip-baseline`: passed through to
  the test script. One run, persistence on: by default the measured sessions
  write every message to their store without log tracing. Do not run the
  non-persistence variant unless the user asks for it.
  The simulator is busy-spun and pinned like the initiator unless
  `--no-tune-simulator` is given.
- `--keep`: skip the destroy step and say so in the report. Only when passed.

Long steps (Terraform, the pipeline, the test) take minutes: run them with
`run_in_background` and wait for the notification, never sleep-poll. Run
everything from the repository root.

## 1. Preflight

1. `git status --short` and `git rev-parse --short HEAD`. A dirty tree is
   allowed but the results are marked `dirty`; say so in the report. Note
   whether `infrastructure/terraform/environments/production/terraform.tfvars`
   is already modified (step 6 restores it only if it was clean).
2. Check `omnibridge-key.pem` exists and `aws sts get-caller-identity` works.
3. `terraform -chdir=infrastructure/terraform/environments/production init -input=false`
   then `terraform -chdir=infrastructure/terraform/environments/production state list`.
   **If the state is not empty, a stack already exists. Stop and ask the user**
   whether to use it and whether to destroy it afterwards: this skill's approval
   covers only a stack it created itself.

## 2. Provision and build (in parallel)

Read the version from `pom.xml` (`<version>` of the root project), then start
both in the background:

- `./scripts/deploy/terraform-deploy.sh --auto-approve -v <version>`
  (init, plan, apply, writes `tf-outputs.json`; it also writes the caller's
  public IP into `terraform.tfvars`).
- `mvn install -DskipTests -q`

If the apply fails, show the Terraform error, go to step 6 (destroy whatever
was created) and report.

## 3. Deploy

`./scripts/deploy/deploy-pipeline.sh --skip-build` uploads the artifacts, waits
for the hosts (`wait-for-hosts.sh`), deploys, restarts and validates. If a
service fails validation, read its log before going on, e.g.
`ssh ... "journalctl -u exchange-simulator --since '15 min ago' --no-pager | tail -100"`
(the SSH through the monitoring host is in CLAUDE.md, IPs in `tf-outputs.json`).
Only continue to the test if `exchange-simulator` and `fix-initiator` are healthy;
otherwise go to step 6 with the diagnosis.

## 4. Test

`./scripts/deploy/run-aws-latency-test.sh -w <warmup> -n <test-orders> -r <rate> [--no-tune-simulator] [--no-persistence] [--skip-baseline]`

It prints the results directory (`logs/aws-latency/<time>/`) and appends the
run to `docs/latency-history.jsonl`. On failure, read `fix-latency.log` and
`sockperf.log` in that directory and the simulator/initiator journal.

## 5. Read the results

- `summary.json` in the results directory: TCP and FIX round trips (µs),
  `fix_rtt_intended_us` (RTT from the intended send time, corrected for
  coordinated omission: the headline FIX number), `fix_rtt_actual_us`,
  `send_slip_us` (how late the pacer sent), commit, instance type, options,
  and `host_checks` per host (affinity,
  busy-spin, isolation, NIC IRQs, preemption). Report every check that is not
  PASS and what it means for the numbers: a FAIL means the configuration under
  test did not take effect; WARNs mean the core was shared (IRQs, other
  threads, an unisolated hyperthread sibling). `host-check-*.txt` has the
  per-second samples and the NIC IRQ affinities.
- Earlier lines of `docs/latency-history.jsonl` with the same `options`
  (including `options.persistence: true`) and `instance_type`: take the most
  recent clean one as the comparison point. Compare `fix_rtt_intended_us` where
  both lines have it; older lines only have `fix_rtt_us` (from actual send).

## 6. Destroy (always, unless `--keep`)

Also after any failure in steps 2 to 5, once the logs above have been read.

1. `./scripts/deploy/terraform-deploy.sh --destroy-only --auto-approve -v <version>`
2. Verify with `terraform -chdir=infrastructure/terraform/environments/production state list`:
   it must be empty. If anything is left, retry the destroy once, then report
   the leftover resources prominently (they cost money).
3. If `terraform.tfvars` was clean in step 1:
   `git checkout -- infrastructure/terraform/environments/production/terraform.tfvars`.

## 7. Record and report

If the test produced results, commit the history line:
`git add docs/latency-history.jsonl` and
`git commit -m "Latency run <commit>: FIX p50 <x> µs, TCP p50 <y> µs"` with the
trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Commit
nothing else, do not push.

Report in this shape:

```
AWS latency run — <commit> (<branch>[, dirty]) on <instance type>
Options: warmup W, test N at R/s, simulator tuning on/off

                 min    p50    p90    p99    max   (µs)
TCP (sockperf)   ...
FIX round trip   ...                          (from actual send)
FIX intended     ...                          (from intended send, CO-corrected)
FIX − TCP p50:   <difference>  (time spent in the engines, not the network)

Host checks: initiator <PASS/WARN/FAIL per check>, simulator <...>

vs <previous commit> (<date>): p50 <±x µs>, p99 <±y µs>
Stack: destroyed (state empty) | kept (--keep) | LEFTOVER: <resources>
```

Then two or three sentences: what changed since the comparison run, anything
odd (max far above p99, sockperf missing, failed sequence realignment, a
service that needed a restart), and where the logs are.
