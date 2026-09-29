def test_generated_client_exposes_every_operation_and_model_the_sdk_uses():
    from agentstore._generated import AuthenticatedClient  # noqa: F401
    from agentstore._generated.types import UNSET  # noqa: F401
    from agentstore._generated.models import (  # noqa: F401
        CompleteRequest, CreateGateRequest, FailRequest, LogBatch, UsageRequest, WriteRecordsRequest,
    )
    from agentstore._generated.api.runtime_context_controller import context
    from agentstore._generated.api.runtime_resource_controller import file, file_content, records, write
    from agentstore._generated.api.runtime_usage_controller import usage
    from agentstore._generated.api.runtime_lifecycle_controller import complete, fail, heartbeat, logs
    from agentstore._generated.api.runtime_gate_controller import create, get
    for op in (context, file, file_content, records, write, usage, complete, fail, heartbeat, logs, create, get):
        assert callable(op.sync_detailed)
