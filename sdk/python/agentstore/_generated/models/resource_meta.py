from __future__ import annotations

from collections.abc import Mapping
from typing import Any, TypeVar

from attrs import define as _attrs_define
from attrs import field as _attrs_field
from typing_extensions import Self

from ..types import UNSET, Unset

T = TypeVar("T", bound="ResourceMeta")


@_attrs_define
class ResourceMeta:
    """
    Attributes:
        resource (str | Unset):
        format_ (str | Unset):
        row_count (int | Unset):
    """

    resource: str | Unset = UNSET
    format_: str | Unset = UNSET
    row_count: int | Unset = UNSET
    additional_properties: dict[str, Any] = _attrs_field(init=False, factory=dict)

    def to_dict(self) -> dict[str, Any]:
        resource = self.resource

        format_ = self.format_

        row_count = self.row_count

        field_dict: dict[str, Any] = {}
        field_dict.update(self.additional_properties)
        field_dict.update({})
        if resource is not UNSET:
            field_dict["resource"] = resource
        if format_ is not UNSET:
            field_dict["format"] = format_
        if row_count is not UNSET:
            field_dict["rowCount"] = row_count

        return field_dict

    @classmethod
    def from_dict(cls, src_dict: Mapping[str, Any]) -> Self:
        d = dict(src_dict)
        resource = d.pop("resource", UNSET)

        format_ = d.pop("format", UNSET)

        row_count = d.pop("rowCount", UNSET)

        resource_meta = cls(
            resource=resource,
            format_=format_,
            row_count=row_count,
        )

        resource_meta.additional_properties = d
        return resource_meta

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
