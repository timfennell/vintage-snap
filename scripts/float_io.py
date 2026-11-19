"""Gives Qualcomm AI Hub's w8a16 Depth Anything V2 float input and output
(ONNX Runtime's Java API has no uint16 tensors): QuantizeLinear in front,
DequantizeLinear behind, with the scale / zero point from metadata.json.
The QNN execution provider folds these into the NPU graph.

    python float_io.py <unzipped depth_anything_v2-onnx-w8a16 dir> <out.onnx>
"""
import json, os, sys
import onnx
from onnx import helper, TensorProto

src_dir, out = sys.argv[1], sys.argv[2]
meta = json.load(open(os.path.join(src_dir, "metadata.json")))["model_files"][0]
qi = meta["inputs"][0]["quantization_parameters"]
qo = meta["outputs"][0]["quantization_parameters"]
m = onnx.load(os.path.join(src_dir, meta["filename"]))   # loads the external .data too
g = m.graph
inp, outp = g.input[0], g.output[0]
in_name, out_name = inp.name, outp.name

g.initializer.extend([
    helper.make_tensor("in_scale", TensorProto.FLOAT, [], [qi["scale"]]),
    helper.make_tensor("in_zp", TensorProto.UINT16, [], [qi.get("zero_point", 0)]),
    helper.make_tensor("out_scale", TensorProto.FLOAT, [], [qo["scale"]]),
    helper.make_tensor("out_zp", TensorProto.UINT16, [], [qo.get("zero_point", 0)]),
])
# Rename the old graph input/output, then put Q / DQ between them and new float ones.
for n in g.node:
    n.input[:] = [in_name + "_q" if x == in_name else x for x in n.input]
    n.output[:] = [out_name + "_q" if x == out_name else x for x in n.output]
g.node.insert(0, helper.make_node("QuantizeLinear", [in_name, "in_scale", "in_zp"], [in_name + "_q"]))
g.node.append(helper.make_node("DequantizeLinear", [out_name + "_q", "out_scale", "out_zp"], [out_name]))
inp.type.tensor_type.elem_type = TensorProto.FLOAT
outp.type.tensor_type.elem_type = TensorProto.FLOAT
if not any(o.domain in ("", "ai.onnx") and o.version >= 21 for o in m.opset_import):
    # uint16 Q/DQ needs opset 21; the com.microsoft domain variants work below that.
    for n in (g.node[0], g.node[-1]):
        n.domain = "com.microsoft"
    if not any(o.domain == "com.microsoft" for o in m.opset_import):
        m.opset_import.append(helper.make_opsetid("com.microsoft", 1))
onnx.save(m, out)   # single file: weights inline (< 2 GB)
print(out, "opsets", [(o.domain, o.version) for o in m.opset_import])
