from __future__ import annotations

from collections.abc import Mapping
from typing import Any, TypeVar

from attrs import define as _attrs_define
from attrs import field as _attrs_field
from typing_extensions import Self

from ..models.granted_scope_type import GrantedScopeType
from ..types import UNSET, Unset

T = TypeVar("T", bound="GrantedScope")


@_attrs_define
class GrantedScope:
    """
    Attributes:
        type_ (GrantedScopeType | Unset):
        resource (str | Unset):
        requires_approval (bool | Unset):
    """

    type_: GrantedScopeType | Unset = UNSET
    resource: str | Unset = UNSET
    requires_approval: bool | Unset = UNSET
    additional_properties: dict[str, Any] = _attrs_field(init=False, factory=dict)

    def to_dict(self) -> dict[str, Any]:
        type_: str | Unset = UNSET
        if not isinstance(self.type_, Unset):
            type_ = self.type_.value

        resource = self.resource

        requires_approval = self.requires_approval

        field_dict: dict[str, Any] = {}
        field_dict.update(self.additional_properties)
        field_dict.update({})
        if type_ is not UNSET:
            field_dict["type"] = type_
        if resource is not UNSET:
            field_dict["resource"] = resource
        if requires_approval is not UNSET:
            field_dict["requiresApproval"] = requires_approval

        return field_dict

    @classmethod
    def from_dict(cls, src_dict: Mapping[str, Any]) -> Self:
        d = dict(src_dict)
        _type_ = d.pop("type", UNSET)
        type_: GrantedScopeType | Unset
        if isinstance(_type_, Unset):
            type_ = UNSET
        else:
            type_ = GrantedScopeType(_type_)

        resource = d.pop("resource", UNSET)

        requires_approval = d.pop("requiresApproval", UNSET)

        granted_scope = cls(
            type_=type_,
            resource=resource,
            requires_approval=requires_approval,
        )

        granted_scope.additional_properties = d
        return granted_scope

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
