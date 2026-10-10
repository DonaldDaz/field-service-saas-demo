#!/usr/bin/env bash
set -euo pipefail
KCADM=/opt/keycloak/bin/kcadm.sh
SERVER=http://keycloak:8080
REALM=fieldflow

until "$KCADM" config credentials --server "$SERVER" --realm master \
  --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null 2>&1; do
  sleep 3
done

if ! "$KCADM" get "realms/$REALM" >/dev/null 2>&1; then
  "$KCADM" create realms -s "realm=$REALM" -s enabled=true >/dev/null
fi

for role in customer operator technician; do
  if ! "$KCADM" get "roles/$role" -r "$REALM" >/dev/null 2>&1; then
    "$KCADM" create roles -r "$REALM" -s "name=$role" >/dev/null
  fi
done

if ! "$KCADM" get clients -r "$REALM" -q clientId=field-web --fields id | grep -q '"id"'; then
  "$KCADM" create clients -r "$REALM" -s clientId=field-web -s enabled=true \
    -s publicClient=true -s standardFlowEnabled=true -s directAccessGrantsEnabled=false \
    -s 'redirectUris=["http://localhost:3000/*"]' -s 'webOrigins=["http://localhost:3000"]' >/dev/null
fi

if ! "$KCADM" get clients -r "$REALM" -q clientId=field-technician --fields id | grep -q '"id"'; then
  "$KCADM" create clients -r "$REALM" -s clientId=field-technician -s enabled=true \
    -s publicClient=true -s standardFlowEnabled=true -s directAccessGrantsEnabled=false \
    -s 'redirectUris=["fieldflow://auth"]' >/dev/null
fi

seed_user() {
  local username="$1" role="$2" password="$3" first="$4" last="$5"
  if ! "$KCADM" get users -r "$REALM" -q "username=$username" --fields id | grep -q '"id"'; then
    "$KCADM" create users -r "$REALM" -s "username=$username" -s enabled=true \
      -s "firstName=$first" -s "lastName=$last" \
      -s "email=$username@fieldflow.example" -s emailVerified=true >/dev/null
  fi
  "$KCADM" set-password -r "$REALM" --username "$username" --new-password "$password"
  "$KCADM" add-roles -r "$REALM" --uusername "$username" --rolename "$role"
}

seed_user customer customer "$CUSTOMER_PASSWORD" Casey Customer
seed_user operator operator "$OPERATOR_PASSWORD" Olivia Operator
seed_user alex technician "$TECHNICIAN_PASSWORD" Alex Morgan
seed_user sam technician "$TECHNICIAN_PASSWORD" Sam Rivera
seed_user jordan technician "$TECHNICIAN_PASSWORD" Jordan Lee
echo "Keycloak realm, clients, roles, and accounts are ready."
