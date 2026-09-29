from __future__ import annotations

from collections.abc import Mapping
from typing import TYPE_CHECKING, Any, TypeVar, cast

from attrs import define as _attrs_define
from attrs import field as _attrs_field
from typing_extensions import Self

from ..types import UNSET, Unset

if TYPE_CHECKING:
    from ..models.create_gate_request_payload import CreateGateRequestPayload


T = TypeVar("T", bound="CreateGateRequest")


@_attrs_define
class CreateGateRequest:
    """
    Attributes:
        action (str):
        summary (str):
        payload (CreateGateRequestPayload):
        affects_resources (list[str] | Unset):
    """

    action: str
    summary: str
    payload: CreateGateRequestPayload
    affects_resources: list[str] | Unset = UNSET
    additional_properties: dict[str, Any] = _attrs_field(init=False, factory=dict)

    def to_dict(self) -> dict[str, Any]:
        action = self.action

        summary = self.summary

        payload = self.payload.to_dict()

        affects_resources: list[str] | Unset = UNSET
        if not isinstance(self.affects_resources, Unset):
            affects_resources = self.affects_resources

        field_dict: dict[str, Any] = {}
        field_dict.update(self.additional_properties)
        field_dict.update(
            {
                "action": action,
                "summary": summary,
                "payload": payload,
            }
        )
        if affects_resources is not UNSET:
            field_dict["affectsResources"] = affects_resources

        return field_dict

    @classmethod
    def from_dict(cls, src_dict: Mapping[str, Any]) -> Self:
        from ..models.create_gate_request_payload import (
            CreateGateRequestPayload,
        )

        d = dict(src_dict)
        action = d.pop("action")

        summary = d.pop("summary")

        payload = CreateGateRequestPayload.from_dict(d.pop("payload"))

        affects_resources = cast(list[str], d.pop("affectsResources", UNSET))

        create_gate_request = cls(
            action=action,
            summary=summary,
            payload=payload,
            affects_resources=affects_resources,
        )

        create_gate_request.additional_properties = d
        return create_gate_request

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
