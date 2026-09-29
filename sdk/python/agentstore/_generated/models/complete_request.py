from __future__ import annotations

from collections.abc import Mapping
from typing import TYPE_CHECKING, Any, TypeVar

from attrs import define as _attrs_define
from attrs import field as _attrs_field
from typing_extensions import Self

from ..types import UNSET, Unset

if TYPE_CHECKING:
    from ..models.complete_request_outputs import CompleteRequestOutputs


T = TypeVar("T", bound="CompleteRequest")


@_attrs_define
class CompleteRequest:
    """
    Attributes:
        summary (str | Unset):
        outputs (CompleteRequestOutputs | Unset):
    """

    summary: str | Unset = UNSET
    outputs: CompleteRequestOutputs | Unset = UNSET
    additional_properties: dict[str, Any] = _attrs_field(init=False, factory=dict)

    def to_dict(self) -> dict[str, Any]:
        summary = self.summary

        outputs: dict[str, Any] | Unset = UNSET
        if not isinstance(self.outputs, Unset):
            outputs = self.outputs.to_dict()

        field_dict: dict[str, Any] = {}
        field_dict.update(self.additional_properties)
        field_dict.update({})
        if summary is not UNSET:
            field_dict["summary"] = summary
        if outputs is not UNSET:
            field_dict["outputs"] = outputs

        return field_dict

    @classmethod
    def from_dict(cls, src_dict: Mapping[str, Any]) -> Self:
        from ..models.complete_request_outputs import (
            CompleteRequestOutputs,
        )

        d = dict(src_dict)
        summary = d.pop("summary", UNSET)

        _outputs = d.pop("outputs", UNSET)
        outputs: CompleteRequestOutputs | Unset
        if isinstance(_outputs, Unset):
            outputs = UNSET
        else:
            outputs = CompleteRequestOutputs.from_dict(_outputs)

        complete_request = cls(
            summary=summary,
            outputs=outputs,
        )

        complete_request.additional_properties = d
        return complete_request

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
