from app.analytics.structural_signature import structural_signature
def test_values_and_order_do_not_change_shape():
    assert structural_signature({"id":"ABC","amount":100})==structural_signature({"amount":999,"id":"XYZ"})
def test_new_field_changes_shape():
    assert structural_signature({"id":"XYZ","amount":999})!=structural_signature({"id":"XYZ","amount":999,"admin":True})
def test_nested_arrays_preserve_item_shape_and_ignore_values():
    assert structural_signature({"items":[{"id":"A","value":1}]})==structural_signature({"items":[{"value":9,"id":"B"}]})
    assert structural_signature({"items":[{"id":"A"}]})!=structural_signature({"items":[{"id":"A","value":1}]})
def test_null_bool_integer_and_float_are_distinct_types():
    assert len({structural_signature({"x":value}) for value in (None,True,1,1.0)})==4
