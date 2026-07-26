(ns io.github.bigconfig-ai.walter.utils
  "Walter's compatibility number.

  Everything else Walter would put here already exists upstream: the process
  helpers, the small YAML emitter and the `GREEN_PAR_*` overlay are `green`'s
  (`green.process`, `green.yaml`, `green.cli`), and the provider registry is
  Once's. Duplicating any of them would let the copies drift.")

(def contract
  "Compatibility number for the launcher that consumes these namespaces and the
  templates under src/resources. Bump it on any change a launcher pinned to an
  older commit could not survive; the launcher refuses to run against a lower
  number and tells the user to repin.

  1: the green rewrite. Desired state is a flat green.edn, the workstation
     lists live under :walter, and the workflow is tofu-compute followed by
     ansible-remote and ansible-local.
  2: validation and the workflow graph move out of the launcher and into this
     library, as walter.validate and walter.workflow. The launcher no longer
     defines its own steps — it calls workflow/workflow and
     describe/describe-file — and `pin` is a maintainer bb task rather than a
     launcher subcommand."
  2)
