import os
from typing import Any

import pymysql
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel


DB_CONFIG = {
    "host": os.environ.get("BEDALARM_DB_HOST", "127.0.0.1"),
    "port": int(os.environ.get("BEDALARM_DB_PORT", "3306")),
    "user": os.environ.get("BEDALARM_DB_USER", "bedalarm"),
    "password": os.environ.get("BEDALARM_DB_PASSWORD", ""),
    "database": os.environ.get("BEDALARM_DB_NAME", "bedalarm"),
    "charset": "utf8mb4",
    "cursorclass": pymysql.cursors.DictCursor,
    "autocommit": True,
}


app = FastAPI(title="Bed Alarm Config API", version="1.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["GET", "PUT", "POST", "OPTIONS"],
    allow_headers=["*"],
)


class LowWeightBed(BaseModel):
    bedId: int
    bedIndex: int | None = None
    bedLabel: str | None = None
    lowWeightPatient: bool = False


class LowWeightSaveRequest(BaseModel):
    hospitalId: int
    hospitalCode: str | None = None
    updatedByUserId: int | None = None
    beds: list[LowWeightBed] = []


class LeaveAlarmAcknowledgementBed(BaseModel):
    bedId: int
    bedIndex: int | None = None
    bedLabel: str | None = None
    leaveAlarmAcknowledged: bool = False


class LeaveAlarmAcknowledgementSaveRequest(BaseModel):
    hospitalId: int
    hospitalCode: str | None = None
    updatedByUserId: int | None = None
    beds: list[LeaveAlarmAcknowledgementBed] = []


class DynamicPressureProfileBed(BaseModel):
    bedId: int
    bedIndex: int | None = None
    bedLabel: str | None = None
    emptyBaseline: list[int] = []
    inBedTemplate: list[int] = []
    baselineUpdateDate: str | None = None
    templateUpdateDate: str | None = None
    baselineUpdatedAt: str | None = None
    templateUpdatedAt: str | None = None


class DynamicPressureProfileSaveRequest(BaseModel):
    hospitalId: int
    hospitalCode: str | None = None
    updatedByUserId: int | None = None
    profiles: list[DynamicPressureProfileBed] = []


class PressureLogicItem(BaseModel):
    patientType: str
    inBedThreshold: int
    twoPointInBedThreshold: int
    leftThreshold: int
    leftConfirmPackets: int


class PressureLogicSaveRequest(BaseModel):
    hospitalId: int = 0
    hospitalCode: str | None = None
    updatedByUserId: int | None = None
    configs: list[PressureLogicItem] = []


def db():
    return pymysql.connect(**DB_CONFIG)


def normalize_code(code: str | None) -> str:
    return (code or "").strip() or "default"


def row_to_low_weight_bed(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "bedId": int(row["bed_id"]),
        "bedIndex": row.get("bed_index"),
        "bedLabel": row.get("bed_label") or "",
        "lowWeightPatient": bool(row.get("low_weight_patient")),
        "updatedAt": row.get("updated_at").isoformat() if row.get("updated_at") else None,
    }


def row_to_leave_alarm_ack_bed(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "bedId": int(row["bed_id"]),
        "bedIndex": row.get("bed_index"),
        "bedLabel": row.get("bed_label") or "",
        "leaveAlarmAcknowledged": bool(row.get("leave_alarm_acknowledged")),
        "updatedAt": row.get("updated_at").isoformat() if row.get("updated_at") else None,
    }


def pressure_list_from_row(row: dict[str, Any], prefix: str) -> list[int]:
    values = [row.get(f"{prefix}_{index}") for index in range(1, 5)]
    if any(value is None for value in values):
        return []
    return [int(value) for value in values]


def normalized_pressure_list4(values: list[int] | None) -> list[int | None]:
    source = values or []
    if len(source) < 4:
        return [None, None, None, None]
    return [max(0, min(255, int(value))) for value in source[:4]]


def row_to_dynamic_pressure_profile(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "bedId": int(row["bed_id"]),
        "bedIndex": row.get("bed_index"),
        "bedLabel": row.get("bed_label") or "",
        "emptyBaseline": pressure_list_from_row(row, "empty_baseline"),
        "inBedTemplate": pressure_list_from_row(row, "in_bed_template"),
        "baselineUpdateDate": row.get("baseline_update_date").isoformat() if row.get("baseline_update_date") else None,
        "templateUpdateDate": row.get("template_update_date").isoformat() if row.get("template_update_date") else None,
        "baselineUpdatedAt": row.get("baseline_updated_at").isoformat() if row.get("baseline_updated_at") else None,
        "templateUpdatedAt": row.get("template_updated_at").isoformat() if row.get("template_updated_at") else None,
        "updatedAt": row.get("updated_at").isoformat() if row.get("updated_at") else None,
    }


@app.get("/api/health")
def health():
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT 1 AS ok")
            return {"ok": cur.fetchone()["ok"] == 1}


@app.get("/api/hospitals/{hospital_code}/low-weight")
def get_low_weight_settings(hospital_code: str, hospitalId: int | None = None):
    code = normalize_code(hospital_code)
    where = "hospital_code=%s"
    params: list[Any] = [code]
    partition = "hospital_code, bed_index, bed_label"
    if hospitalId is not None:
        where = "hospital_id=%s"
        params = [hospitalId]
        partition = "hospital_id, bed_index, bed_label"
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                SELECT bed_id, bed_index, bed_label, low_weight_patient, updated_at
                FROM (
                    SELECT
                        bed_id, bed_index, bed_label, low_weight_patient, updated_at,
                        ROW_NUMBER() OVER (
                            PARTITION BY {partition}
                            ORDER BY updated_at DESC, id DESC
                        ) AS rn
                    FROM low_weight_patient_setting
                    WHERE {where}
                ) latest
                WHERE rn=1
                ORDER BY bed_index IS NULL, bed_index, bed_label, bed_id
                """,
                params,
            )
            rows = cur.fetchall()
    return {
        "hospitalCode": code,
        "hospitalId": hospitalId,
        "beds": [row_to_low_weight_bed(row) for row in rows],
    }


@app.put("/api/hospitals/{hospital_code}/low-weight")
def save_low_weight_settings(hospital_code: str, request: LowWeightSaveRequest):
    code = normalize_code(request.hospitalCode or hospital_code)
    if request.hospitalId <= 0:
        raise HTTPException(status_code=400, detail="hospitalId must be positive")
    with db() as conn:
        with conn.cursor() as cur:
            for bed in request.beds:
                cur.execute(
                    """
                    INSERT INTO low_weight_patient_setting (
                        hospital_id, hospital_code, bed_id, bed_index, bed_label,
                        low_weight_patient, updated_by_user_id
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s)
                    ON DUPLICATE KEY UPDATE
                        hospital_code = VALUES(hospital_code),
                        bed_id = VALUES(bed_id),
                        bed_index = VALUES(bed_index),
                        bed_label = VALUES(bed_label),
                        low_weight_patient = VALUES(low_weight_patient),
                        updated_by_user_id = VALUES(updated_by_user_id),
                        updated_at = CURRENT_TIMESTAMP(6)
                    """,
                    (
                        request.hospitalId,
                        code,
                        bed.bedId,
                        bed.bedIndex,
                        bed.bedLabel,
                        1 if bed.lowWeightPatient else 0,
                        request.updatedByUserId,
                    ),
                )
    return get_low_weight_settings(code, request.hospitalId)


@app.get("/api/hospitals/{hospital_code}/leave-alarm-ack")
def get_leave_alarm_acknowledgements(hospital_code: str, hospitalId: int | None = None):
    code = normalize_code(hospital_code)
    where = "hospital_code=%s"
    params: list[Any] = [code]
    partition = "hospital_code, bed_index, bed_label"
    if hospitalId is not None:
        where = "hospital_id=%s"
        params = [hospitalId]
        partition = "hospital_id, bed_index, bed_label"
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                SELECT bed_id, bed_index, bed_label, leave_alarm_acknowledged, updated_at
                FROM (
                    SELECT
                        bed_id, bed_index, bed_label, leave_alarm_acknowledged, updated_at,
                        ROW_NUMBER() OVER (
                            PARTITION BY {partition}
                            ORDER BY updated_at DESC, id DESC
                        ) AS rn
                    FROM leave_alarm_acknowledgement_setting
                    WHERE {where}
                ) latest
                WHERE rn=1
                ORDER BY bed_index IS NULL, bed_index, bed_label, bed_id
                """,
                params,
            )
            rows = cur.fetchall()
    return {
        "hospitalCode": code,
        "hospitalId": hospitalId,
        "beds": [row_to_leave_alarm_ack_bed(row) for row in rows],
    }


@app.put("/api/hospitals/{hospital_code}/leave-alarm-ack")
def save_leave_alarm_acknowledgements(hospital_code: str, request: LeaveAlarmAcknowledgementSaveRequest):
    code = normalize_code(request.hospitalCode or hospital_code)
    if request.hospitalId <= 0:
        raise HTTPException(status_code=400, detail="hospitalId must be positive")
    with db() as conn:
        with conn.cursor() as cur:
            for bed in request.beds:
                cur.execute(
                    """
                    INSERT INTO leave_alarm_acknowledgement_setting (
                        hospital_id, hospital_code, bed_id, bed_index, bed_label,
                        leave_alarm_acknowledged, updated_by_user_id
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s)
                    ON DUPLICATE KEY UPDATE
                        hospital_code = VALUES(hospital_code),
                        bed_id = VALUES(bed_id),
                        bed_index = VALUES(bed_index),
                        bed_label = VALUES(bed_label),
                        leave_alarm_acknowledged = VALUES(leave_alarm_acknowledged),
                        updated_by_user_id = VALUES(updated_by_user_id),
                        updated_at = CURRENT_TIMESTAMP(6)
                    """,
                    (
                        request.hospitalId,
                        code,
                        bed.bedId,
                        bed.bedIndex,
                        bed.bedLabel,
                        1 if bed.leaveAlarmAcknowledged else 0,
                        request.updatedByUserId,
                    ),
                )
    return get_leave_alarm_acknowledgements(code, request.hospitalId)


@app.get("/api/hospitals/{hospital_code}/dynamic-pressure-profiles")
def get_dynamic_pressure_profiles(hospital_code: str, hospitalId: int | None = None):
    code = normalize_code(hospital_code)
    where = "hospital_code=%s"
    params: list[Any] = [code]
    partition = "hospital_code, bed_index, bed_label"
    if hospitalId is not None:
        where = "hospital_id=%s"
        params = [hospitalId]
        partition = "hospital_id, bed_index, bed_label"
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                SELECT *
                FROM (
                    SELECT
                        *,
                        ROW_NUMBER() OVER (
                            PARTITION BY {partition}
                            ORDER BY updated_at DESC, id DESC
                        ) AS rn
                    FROM bed_pressure_dynamic_profile
                    WHERE {where}
                ) latest
                WHERE rn=1
                ORDER BY bed_index IS NULL, bed_index, bed_label, bed_id
                """,
                params,
            )
            rows = cur.fetchall()
    return {
        "hospitalCode": code,
        "hospitalId": hospitalId,
        "profiles": [row_to_dynamic_pressure_profile(row) for row in rows],
    }


@app.put("/api/hospitals/{hospital_code}/dynamic-pressure-profiles")
def save_dynamic_pressure_profiles(hospital_code: str, request: DynamicPressureProfileSaveRequest):
    code = normalize_code(request.hospitalCode or hospital_code)
    if request.hospitalId <= 0:
        raise HTTPException(status_code=400, detail="hospitalId must be positive")
    with db() as conn:
        with conn.cursor() as cur:
            for profile in request.profiles:
                empty = normalized_pressure_list4(profile.emptyBaseline)
                template = normalized_pressure_list4(profile.inBedTemplate)
                cur.execute(
                    """
                    INSERT INTO bed_pressure_dynamic_profile (
                        hospital_id, hospital_code, bed_id, bed_index, bed_label,
                        empty_baseline_1, empty_baseline_2, empty_baseline_3, empty_baseline_4,
                        in_bed_template_1, in_bed_template_2, in_bed_template_3, in_bed_template_4,
                        baseline_update_date, template_update_date,
                        baseline_updated_at, template_updated_at, updated_by_user_id
                    ) VALUES (
                        %s, %s, %s, %s, %s,
                        %s, %s, %s, %s,
                        %s, %s, %s, %s,
                        %s, %s,
                        %s, %s, %s
                    )
                    ON DUPLICATE KEY UPDATE
                        hospital_code = VALUES(hospital_code),
                        bed_id = VALUES(bed_id),
                        bed_index = VALUES(bed_index),
                        bed_label = VALUES(bed_label),
                        empty_baseline_1 = COALESCE(VALUES(empty_baseline_1), empty_baseline_1),
                        empty_baseline_2 = COALESCE(VALUES(empty_baseline_2), empty_baseline_2),
                        empty_baseline_3 = COALESCE(VALUES(empty_baseline_3), empty_baseline_3),
                        empty_baseline_4 = COALESCE(VALUES(empty_baseline_4), empty_baseline_4),
                        in_bed_template_1 = COALESCE(VALUES(in_bed_template_1), in_bed_template_1),
                        in_bed_template_2 = COALESCE(VALUES(in_bed_template_2), in_bed_template_2),
                        in_bed_template_3 = COALESCE(VALUES(in_bed_template_3), in_bed_template_3),
                        in_bed_template_4 = COALESCE(VALUES(in_bed_template_4), in_bed_template_4),
                        baseline_update_date = COALESCE(VALUES(baseline_update_date), baseline_update_date),
                        template_update_date = COALESCE(VALUES(template_update_date), template_update_date),
                        baseline_updated_at = COALESCE(VALUES(baseline_updated_at), baseline_updated_at),
                        template_updated_at = COALESCE(VALUES(template_updated_at), template_updated_at),
                        updated_by_user_id = VALUES(updated_by_user_id),
                        updated_at = CURRENT_TIMESTAMP(6)
                    """,
                    (
                        request.hospitalId,
                        code,
                        profile.bedId,
                        profile.bedIndex,
                        profile.bedLabel,
                        *empty,
                        *template,
                        profile.baselineUpdateDate,
                        profile.templateUpdateDate,
                        profile.baselineUpdatedAt,
                        profile.templateUpdatedAt,
                        request.updatedByUserId,
                    ),
                )
    return get_dynamic_pressure_profiles(code, request.hospitalId)


def pressure_row_to_api(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "patientType": row["patient_type"],
        "inBedThreshold": int(row["in_bed_threshold"]),
        "twoPointInBedThreshold": int(row["two_point_in_bed_threshold"]),
        "leftThreshold": int(row["left_threshold"]),
        "leftConfirmPackets": int(row["left_confirm_packets"]),
        "updatedAt": row.get("updated_at").isoformat() if row.get("updated_at") else None,
    }


@app.get("/api/hospitals/{hospital_code}/pressure-logic")
def get_pressure_logic_settings(hospital_code: str, hospitalId: int | None = None):
    code = normalize_code(hospital_code)
    target_id = hospitalId if hospitalId is not None else 0
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT *
                FROM pressure_logic_setting
                WHERE hospital_id=%s
                ORDER BY FIELD(patient_type, 'NORMAL', 'LOW_WEIGHT'), patient_type
                """,
                (target_id,),
            )
            rows = cur.fetchall()
            if target_id != 0 and len(rows) < 2:
                cur.execute(
                    """
                    SELECT *
                    FROM pressure_logic_setting
                    WHERE hospital_id=0
                    ORDER BY FIELD(patient_type, 'NORMAL', 'LOW_WEIGHT'), patient_type
                    """
                )
                rows = cur.fetchall()
    return {
        "hospitalCode": code,
        "hospitalId": target_id,
        "configs": [pressure_row_to_api(row) for row in rows],
    }


@app.put("/api/hospitals/{hospital_code}/pressure-logic")
def save_pressure_logic_settings(hospital_code: str, request: PressureLogicSaveRequest):
    code = normalize_code(request.hospitalCode or hospital_code)
    hospital_id = request.hospitalId
    if hospital_id < 0:
        raise HTTPException(status_code=400, detail="hospitalId must be non-negative")
    with db() as conn:
        with conn.cursor() as cur:
            for item in request.configs:
                patient_type = item.patientType.strip().upper()
                if patient_type not in {"NORMAL", "LOW_WEIGHT"}:
                    raise HTTPException(status_code=400, detail=f"invalid patientType: {item.patientType}")
                cur.execute(
                    """
                    INSERT INTO pressure_logic_setting (
                        hospital_id, hospital_code, patient_type,
                        in_bed_threshold, two_point_in_bed_threshold,
                        left_threshold, left_confirm_packets, updated_by_user_id
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                    ON DUPLICATE KEY UPDATE
                        hospital_code = VALUES(hospital_code),
                        in_bed_threshold = VALUES(in_bed_threshold),
                        two_point_in_bed_threshold = VALUES(two_point_in_bed_threshold),
                        left_threshold = VALUES(left_threshold),
                        left_confirm_packets = VALUES(left_confirm_packets),
                        updated_by_user_id = VALUES(updated_by_user_id)
                    """,
                    (
                        hospital_id,
                        code,
                        patient_type,
                        item.inBedThreshold,
                        item.twoPointInBedThreshold,
                        item.leftThreshold,
                        item.leftConfirmPackets,
                        request.updatedByUserId,
                    ),
                )
    return get_pressure_logic_settings(code, hospital_id)
