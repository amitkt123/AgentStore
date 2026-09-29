from __future__ import annotations

from collections.abc import Mapping
from typing import Any, TypeVar

from attrs import define as _attrs_define
from attrs import field as _attrs_field
from typing_extensions import Self

from ..types import UNSET, Unset

T = TypeVar("T", bound="UsageResponse")


@_attrs_define
class UsageResponse:
    """
    Attributes:
        charged (float | Unset):
        total_charged (float | Unset):
        cost_cap_remaining (float | Unset):
    """

    charged: float | Unset = UNSET
    total_charged: float | Unset = UNSET
    cost_cap_remaining: float | Unset = UNSET
    additional_properties: dict[str, Any] = _attrs_field(init=False, factory=dict)

    def to_dict(self) -> dict[str, Any]:
        charged = self.charged

        total_charged = self.total_charged

        cost_cap_remaining = self.cost_cap_remaining

        field_dict: dict[str, Any] = {}
        field_dict.update(self.additional_properties)
        field_dict.update({})
        if charged is not UNSET:
            field_dict["charged"] = charged
        if total_charged is not UNSET:
            field_dict["totalCharged"] = total_charged
        if cost_cap_remaining is not UNSET:
            field_dict["costCapRemaining"] = cost_cap_remaining

        return field_dict

    @classmethod
    def from_dict(cls, src_dict: Mapping[str, Any]) -> Self:
        d = dict(src_dict)
        charged = d.pop("charged", UNSET)

        total_charged = d.pop("totalCharged", UNSET)

        cost_cap_remaining = d.pop("costCapRemaining", UNSET)

        usage_response = cls(
            charged=charged,
            total_charged=total_charged,
            cost_cap_remaining=cost_cap_remaining,
        )

        usage_response.additional_properties = d
        return usage_response

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
