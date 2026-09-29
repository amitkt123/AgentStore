from __future__ import annotations

from collections.abc import Mapping
from typing import TYPE_CHECKING, Any, TypeVar
from uuid import UUID

from attrs import define as _attrs_define
from attrs import field as _attrs_field
from typing_extensions import Self

from ..models.context_response_mode import ContextResponseMode
from ..types import UNSET, Unset

if TYPE_CHECKING:
    from ..models.agent_version_info import AgentVersionInfo
    from ..models.context_response_input import ContextResponseInput
    from ..models.granted_scope import GrantedScope
    from ..models.limits import Limits


T = TypeVar("T", bound="ContextResponse")


@_attrs_define
class ContextResponse:
    """
    Attributes:
        run_id (UUID | Unset):
        mode (ContextResponseMode | Unset):
        agent_version (AgentVersionInfo | Unset):
        input_ (ContextResponseInput | Unset):
        granted_scopes (list[GrantedScope] | Unset):
        limits (Limits | Unset):
    """

    run_id: UUID | Unset = UNSET
    mode: ContextResponseMode | Unset = UNSET
    agent_version: AgentVersionInfo | Unset = UNSET
    input_: ContextResponseInput | Unset = UNSET
    granted_scopes: list[GrantedScope] | Unset = UNSET
    limits: Limits | Unset = UNSET
    additional_properties: dict[str, Any] = _attrs_field(init=False, factory=dict)

    def to_dict(self) -> dict[str, Any]:
        run_id: str | Unset = UNSET
        if not isinstance(self.run_id, Unset):
            run_id = str(self.run_id)

        mode: str | Unset = UNSET
        if not isinstance(self.mode, Unset):
            mode = self.mode.value

        agent_version: dict[str, Any] | Unset = UNSET
        if not isinstance(self.agent_version, Unset):
            agent_version = self.agent_version.to_dict()

        input_: dict[str, Any] | Unset = UNSET
        if not isinstance(self.input_, Unset):
            input_ = self.input_.to_dict()

        granted_scopes: list[dict[str, Any]] | Unset = UNSET
        if not isinstance(self.granted_scopes, Unset):
            granted_scopes = []
            for granted_scopes_item_data in self.granted_scopes:
                granted_scopes_item = granted_scopes_item_data.to_dict()
                granted_scopes.append(granted_scopes_item)

        limits: dict[str, Any] | Unset = UNSET
        if not isinstance(self.limits, Unset):
            limits = self.limits.to_dict()

        field_dict: dict[str, Any] = {}
        field_dict.update(self.additional_properties)
        field_dict.update({})
        if run_id is not UNSET:
            field_dict["runId"] = run_id
        if mode is not UNSET:
            field_dict["mode"] = mode
        if agent_version is not UNSET:
            field_dict["agentVersion"] = agent_version
        if input_ is not UNSET:
            field_dict["input"] = input_
        if granted_scopes is not UNSET:
            field_dict["grantedScopes"] = granted_scopes
        if limits is not UNSET:
            field_dict["limits"] = limits

        return field_dict

    @classmethod
    def from_dict(cls, src_dict: Mapping[str, Any]) -> Self:
        from ..models.agent_version_info import AgentVersionInfo
        from ..models.context_response_input import (
            ContextResponseInput,
        )
        from ..models.granted_scope import GrantedScope
        from ..models.limits import Limits

        d = dict(src_dict)
        _run_id = d.pop("runId", UNSET)
        run_id: UUID | Unset
        if isinstance(_run_id, Unset):
            run_id = UNSET
        else:
            run_id = UUID(_run_id)

        _mode = d.pop("mode", UNSET)
        mode: ContextResponseMode | Unset
        if isinstance(_mode, Unset):
            mode = UNSET
        else:
            mode = ContextResponseMode(_mode)

        _agent_version = d.pop("agentVersion", UNSET)
        agent_version: AgentVersionInfo | Unset
        if isinstance(_agent_version, Unset):
            agent_version = UNSET
        else:
            agent_version = AgentVersionInfo.from_dict(_agent_version)

        _input_ = d.pop("input", UNSET)
        input_: ContextResponseInput | Unset
        if isinstance(_input_, Unset):
            input_ = UNSET
        else:
            input_ = ContextResponseInput.from_dict(_input_)

        _granted_scopes = d.pop("grantedScopes", UNSET)
        granted_scopes: list[GrantedScope] | Unset = UNSET
        if _granted_scopes is not UNSET:
            granted_scopes = []
            for granted_scopes_item_data in _granted_scopes:
                granted_scopes_item = GrantedScope.from_dict(granted_scopes_item_data)

                granted_scopes.append(granted_scopes_item)

        _limits = d.pop("limits", UNSET)
        limits: Limits | Unset
        if isinstance(_limits, Unset):
            limits = UNSET
        else:
            limits = Limits.from_dict(_limits)

        context_response = cls(
            run_id=run_id,
            mode=mode,
            agent_version=agent_version,
            input_=input_,
            granted_scopes=granted_scopes,
            limits=limits,
        )

        context_response.additional_properties = d
        return context_response

    @property
    def additional_keys(self) -> list[str]:
        return list(self.additional_properties.keys())

    def __getitem__(self, key: str) -> Any:
        return self.additional_properties[key]

    def __setitem__(self, key: str, value: Any) -> None:
        self.additional_properties[key] = value

    def __delitem__(self, key: str) -> None:
        del self.additional_properties[key]

    def __contains__(self, key: str) -> bool:
        return key in self.additional_properties
